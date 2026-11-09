import coremltools as ct
from coremltools.converters.mil import Builder as mb
from coremltools.converters.mil.mil import types

@mb.program(input_specs=[mb.TensorSpec(shape=(1,3,4,4), dtype=types.fp32)])
def add1(x):
    return mb.add(x=x, y=1.0)

model = ct.convert(add1, minimum_deployment_target=ct.target.macOS13)
spec = model.get_spec()
for i, f in enumerate(spec.description.input):
    print("input", i, repr(f.name), f.type.WhichOneof("Type"))
for o, f in enumerate(spec.description.output):
    print("output", o, repr(f.name), f.type.WhichOneof("Type"))
model.save("test/almaz/fixtures/add1.mlpackage")
print("saved add1.mlpackage")
