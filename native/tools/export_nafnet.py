#!/usr/bin/env python3
"""Export an official NAFNet checkpoint (megvii-research/NAFNet; MIT, parts from BasicSR under Apache-2.0) to
ONNX for the almaz inference layer.

The architecture is defined inline (a faithful transcription of
basicsr/models/archs/NAFNet_arch.py) so no NAFNet/basicsr checkout is needed.

Usage:
  python export_nafnet.py <checkpoint.pth> <output.onnx> --arch sidd|reds [--size 512]

  --arch sidd   NAFNet-SIDD-width64  (real-noise denoise)
  --arch reds   NAFNet-REDS-width64  (deblur, incl. compression artifacts)
  --size N      static NxN input (CoreML compiles static shapes best); 0 = dynamic H/W

Checkpoints (from the NAFNet author's Hugging Face space):
  https://huggingface.co/spaces/chuxiaojie/NAFNet/resolve/main/NAFNet-SIDD-width64.pth
  https://huggingface.co/spaces/chuxiaojie/NAFNet/resolve/main/NAFNet-REDS-width64.pth

Input/output: "image" -> "output", NCHW float32 RGB in [0,1]. H and W must be
multiples of 16 (the app's tiler guarantees this).
"""

import argparse

import torch
import torch.nn as nn

ARCHS = {
    "sidd": dict(width=64, middle_blk_num=12, enc_blk_nums=[2, 2, 4, 8], dec_blk_nums=[2, 2, 2, 2]),
    "reds": dict(width=64, middle_blk_num=1, enc_blk_nums=[1, 1, 1, 28], dec_blk_nums=[1, 1, 1, 1]),
}


class LayerNorm2d(nn.Module):
    def __init__(self, channels, eps=1e-6):
        super().__init__()
        self.weight = nn.Parameter(torch.ones(channels))
        self.bias = nn.Parameter(torch.zeros(channels))
        self.eps = eps

    def forward(self, x):
        mu = x.mean(1, keepdim=True)
        var = (x - mu).pow(2).mean(1, keepdim=True)
        y = (x - mu) / (var + self.eps).sqrt()
        return self.weight.view(1, -1, 1, 1) * y + self.bias.view(1, -1, 1, 1)


class SimpleGate(nn.Module):
    def forward(self, x):
        x1, x2 = x.chunk(2, dim=1)
        return x1 * x2


class NAFBlock(nn.Module):
    def __init__(self, c, dw_expand=2, ffn_expand=2):
        super().__init__()
        dw = c * dw_expand
        self.conv1 = nn.Conv2d(c, dw, 1)
        self.conv2 = nn.Conv2d(dw, dw, 3, padding=1, groups=dw)
        self.conv3 = nn.Conv2d(dw // 2, c, 1)
        self.sca = nn.Sequential(nn.AdaptiveAvgPool2d(1), nn.Conv2d(dw // 2, dw // 2, 1))
        self.sg = SimpleGate()
        ffn = ffn_expand * c
        self.conv4 = nn.Conv2d(c, ffn, 1)
        self.conv5 = nn.Conv2d(ffn // 2, c, 1)
        self.norm1 = LayerNorm2d(c)
        self.norm2 = LayerNorm2d(c)
        self.beta = nn.Parameter(torch.zeros((1, c, 1, 1)))
        self.gamma = nn.Parameter(torch.zeros((1, c, 1, 1)))

    def forward(self, inp):
        x = self.norm1(inp)
        x = self.conv2(self.conv1(x))
        x = self.sg(x)
        x = x * self.sca(x)
        x = self.conv3(x)
        y = inp + x * self.beta
        x = self.conv5(self.sg(self.conv4(self.norm2(y))))
        return y + x * self.gamma


class NAFNet(nn.Module):
    def __init__(self, img_channel=3, width=16, middle_blk_num=1, enc_blk_nums=(), dec_blk_nums=()):
        super().__init__()
        self.intro = nn.Conv2d(img_channel, width, 3, padding=1)
        self.ending = nn.Conv2d(width, img_channel, 3, padding=1)
        self.encoders, self.decoders = nn.ModuleList(), nn.ModuleList()
        self.ups, self.downs = nn.ModuleList(), nn.ModuleList()
        chan = width
        for num in enc_blk_nums:
            self.encoders.append(nn.Sequential(*[NAFBlock(chan) for _ in range(num)]))
            self.downs.append(nn.Conv2d(chan, 2 * chan, 2, 2))
            chan *= 2
        self.middle_blks = nn.Sequential(*[NAFBlock(chan) for _ in range(middle_blk_num)])
        for num in dec_blk_nums:
            self.ups.append(nn.Sequential(nn.Conv2d(chan, chan * 2, 1, bias=False), nn.PixelShuffle(2)))
            chan //= 2
            self.decoders.append(nn.Sequential(*[NAFBlock(chan) for _ in range(num)]))

    def forward(self, inp):
        x = self.intro(inp)
        skips = []
        for enc, down in zip(self.encoders, self.downs):
            x = enc(x)
            skips.append(x)
            x = down(x)
        x = self.middle_blks(x)
        for dec, up, skip in zip(self.decoders, self.ups, skips[::-1]):
            x = up(x) + skip
            x = dec(x)
        return (self.ending(x) + inp).clamp(0.0, 1.0)


def load(checkpoint, arch):
    net = NAFNet(img_channel=3, **ARCHS[arch])
    ckpt = torch.load(checkpoint, map_location="cpu", weights_only=True)
    state = ckpt.get("params", ckpt)
    net.load_state_dict(state, strict=True)
    return net.eval()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("checkpoint")
    ap.add_argument("output")
    ap.add_argument("--arch", choices=sorted(ARCHS), required=True)
    ap.add_argument("--size", type=int, default=512)
    a = ap.parse_args()
    net = load(a.checkpoint, a.arch)
    n = a.size or 256
    kw = {}
    if a.size == 0:
        kw["dynamic_axes"] = {"image": {2: "h", 3: "w"}, "output": {2: "h", 3: "w"}}
    with torch.no_grad():
        torch.onnx.export(net, torch.rand(1, 3, n, n), a.output, input_names=["image"],
                          output_names=["output"], opset_version=17,
                          do_constant_folding=True, dynamo=False, **kw)
    print("wrote", a.output)
