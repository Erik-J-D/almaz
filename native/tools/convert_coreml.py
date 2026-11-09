import coremltools as ct
import sys

for name in ["identity", "add1"]:
    src = f"test/almaz/fixtures/{name}.onnx"
    model = ct.converters.convert(src, minimum_deployment_target=ct.target.macOS13)
    spec = model.get_spec()
    print(f"=== {name} ===")
    for i, feat in enumerate(spec.description.input):
        print(f"  input[{i}]: name={feat.name} type={feat.type.WhichOneof('Type')}")
    for o, feat in enumerate(spec.description.output):
        print(f"  output[{o}]: name={feat.name} type={feat.type.WhichOneof('Type')}")
    out = f"test/almaz/fixtures/{name}.mlpackage"
    model.save(out)
    print(f"  saved {out}")
