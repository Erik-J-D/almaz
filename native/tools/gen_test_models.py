import onnx
from onnx import helper, TensorProto
import numpy as np

out = "test/almaz/fixtures"
shape = [1, 3, 4, 4]

# identity: output = input
X = helper.make_tensor_value_info("input", TensorProto.FLOAT, shape)
Y = helper.make_tensor_value_info("output", TensorProto.FLOAT, shape)
node = helper.make_node("Identity", ["input"], ["output"])
graph = helper.make_graph([node], "identity", [X], [Y])
model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 13)])
onnx.save(model, f"{out}/identity.onnx")

# add1: output = input + 1.0 (a real computation, proves marshalling both ways)
c = helper.make_tensor("c", TensorProto.FLOAT, [1], np.array([1.0], dtype=np.float32))
X = helper.make_tensor_value_info("input", TensorProto.FLOAT, shape)
Y = helper.make_tensor_value_info("output", TensorProto.FLOAT, shape)
node = helper.make_node("Add", ["input", "c"], ["output"])
graph = helper.make_graph([node], "add1", [X], [Y], [c])
model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 13)])
onnx.save(model, f"{out}/add1.onnx")

print("wrote identity.onnx and add1.onnx")
