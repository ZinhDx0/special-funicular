#!/data/data/com.termux/files/usr/bin/python3
"""
DepthMap Termux inference script.

Usage:
    python3 depth_termux.py <task_json_path>

Task JSON format:
{
    "input_image": "/path/to/input.png",
    "model_path": "/path/to/model.onnx",
    "output_image": "/path/to/output.png"
}

Output: writes completion JSON next to task file.
"""

import json
import sys
import os
import struct

import numpy as np

try:
    import onnxruntime as ort
except ImportError:
    ort = None

try:
    from PIL import Image
except ImportError:
    Image = None

# Inferno colormap (256 entries)
INFERNO_RED = [
    0, 1, 1, 1, 2, 2, 2, 3, 4, 4, 5, 6, 7, 8, 9, 10,
    11, 12, 13, 14, 16, 17, 18, 20, 21, 22, 24, 25, 27, 28, 30, 31,
    33, 35, 36, 38, 40, 41, 43, 45, 47, 49, 50, 52, 54, 56, 57, 59,
    61, 62, 64, 66, 68, 69, 71, 73, 74, 76, 77, 79, 81, 82, 84, 85,
    87, 89, 90, 92, 93, 95, 97, 98, 100, 101, 103, 105, 106, 108, 109, 111,
    113, 114, 116, 117, 119, 120, 122, 124, 125, 127, 128, 130, 132, 133, 135, 136,
    138, 140, 141, 143, 144, 146, 147, 149, 151, 152, 154, 155, 157, 159, 160, 162,
    163, 165, 166, 168, 169, 171, 173, 174, 176, 177, 179, 180, 182, 183, 185, 186,
    188, 189, 191, 192, 193, 195, 196, 198, 199, 200, 202, 203, 204, 206, 207, 208,
    210, 211, 212, 213, 215, 216, 217, 218, 219, 221, 222, 223, 224, 225, 226, 227,
    228, 229, 230, 231, 232, 233, 234, 235, 235, 236, 237, 238, 239, 239, 240, 241,
    241, 242, 243, 243, 244, 245, 245, 246, 246, 247, 247, 248, 248, 248, 249, 249,
    249, 250, 250, 250, 251, 251, 251, 251, 251, 252, 252, 252, 252, 252, 252, 252,
    252, 252, 252, 252, 252, 252, 251, 251, 251, 251, 251, 250, 250, 250, 250, 249,
    249, 249, 248, 248, 247, 247, 246, 246, 245, 245, 244, 244, 244, 243, 243, 242,
    242, 242, 241, 241, 241, 241, 242, 242, 243, 243, 244, 245, 246, 248, 249, 250,
    252
]

INFERNO_GREEN = [
    0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 4, 4, 5, 5, 6, 7,
    7, 8, 8, 9, 9, 10, 10, 11, 11, 11, 12, 12, 12, 12, 12, 12,
    12, 12, 12, 12, 11, 11, 11, 11, 10, 10, 10, 10, 9, 9, 9, 9,
    9, 9, 10, 10, 10, 10, 11, 11, 12, 12, 13, 13, 14, 14, 15, 15,
    16, 16, 17, 18, 18, 19, 19, 20, 21, 21, 22, 22, 23, 24, 24, 25,
    25, 26, 26, 27, 28, 28, 29, 29, 30, 30, 31, 32, 32, 33, 33, 34,
    34, 35, 35, 36, 37, 37, 38, 38, 39, 39, 40, 41, 41, 42, 42, 43,
    44, 44, 45, 46, 46, 47, 48, 48, 49, 50, 50, 51, 52, 53, 53, 54,
    55, 56, 57, 58, 58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69,
    70, 71, 72, 74, 75, 76, 77, 78, 80, 81, 82, 83, 85, 86, 87, 89,
    90, 92, 93, 94, 96, 97, 99, 100, 102, 103, 105, 106, 108, 110, 111, 113,
    115, 116, 118, 120, 121, 123, 125, 126, 128, 130, 132, 133, 135, 137, 139, 140,
    142, 144, 146, 148, 150, 151, 153, 155, 157, 159, 161, 163, 165, 166, 168, 170,
    172, 174, 176, 178, 180, 182, 184, 186, 188, 190, 192, 194, 196, 198, 199, 201,
    203, 205, 207, 209, 211, 213, 215, 217, 219, 221, 223, 225, 227, 229, 230, 232,
    234, 236, 237, 239, 241, 242, 244, 245, 246, 248, 249, 250, 251, 252, 253, 255
]

INFERNO_BLUE = [
    4, 5, 6, 8, 10, 12, 14, 16, 18, 20, 23, 25, 27, 29, 31, 34,
    36, 38, 41, 43, 45, 48, 50, 52, 55, 57, 60, 62, 65, 67, 69, 72,
    74, 76, 79, 81, 83, 85, 87, 89, 91, 92, 94, 95, 97, 98, 99, 100,
    101, 102, 103, 104, 104, 105, 106, 106, 107, 107, 108, 108, 108, 109, 109, 109,
    110, 110, 110, 110, 110, 110, 110, 110, 110, 110, 110, 110, 110, 110, 110, 110,
    110, 110, 110, 110, 110, 110, 110, 109, 109, 109, 109, 109, 108, 108, 108, 107,
    107, 107, 106, 106, 105, 105, 105, 104, 104, 103, 103, 102, 102, 101, 100, 100,
    99, 99, 98, 97, 96, 96, 95, 94, 94, 93, 92, 91, 90, 90, 89, 88,
    87, 86, 85, 84, 83, 82, 81, 80, 79, 78, 77, 76, 75, 74, 73, 72,
    71, 70, 69, 68, 67, 66, 65, 63, 62, 61, 60, 59, 58, 56, 55, 54,
    53, 52, 51, 49, 48, 47, 46, 45, 43, 42, 41, 40, 38, 37, 36, 35,
    33, 32, 31, 29, 28, 27, 25, 24, 23, 21, 20, 19, 18, 16, 15, 14,
    12, 11, 10, 9, 8, 7, 7, 6, 6, 6, 6, 7, 7, 8, 9, 10,
    12, 13, 15, 17, 18, 20, 22, 24, 26, 29, 31, 33, 35, 38, 40, 42,
    45, 47, 50, 53, 55, 58, 61, 64, 67, 70, 73, 76, 79, 83, 86, 90,
    93, 97, 101, 105, 109, 113, 117, 121, 125, 130, 134, 138, 142, 146, 150, 154,
    157, 161, 164
]


def write_result(task_dir, status, output_path=None, error=None):
    result = {"status": status}
    if output_path:
        result["output"] = output_path
    if error:
        result["error"] = error
    with open(os.path.join(task_dir, "result.json"), "w") as f:
        json.dump(result, f)


def apply_inferno(depth_array, width, height):
    """Apply Inferno colormap to a float depth array."""
    import struct
    min_val = float(np.min(depth_array))
    max_val = float(np.max(depth_array))
    span = max_val - min_val

    pixels = bytearray(width * height * 4)
    if span > 1e-6:
        for i in range(len(depth_array)):
            idx = int((depth_array[i] - min_val) / span * 255)
            idx = max(0, min(255, idx))
            off = i * 4
            pixels[off] = INFERNO_RED[idx]
            pixels[off + 1] = INFERNO_GREEN[idx]
            pixels[off + 2] = INFERNO_BLUE[idx]
            pixels[off + 3] = 255

    img = Image.frombuffer("RGBA", (width, height), bytes(pixels), "raw", "RGBA", 0, 1)
    return img.convert("RGB")


def run_inference(task):
    input_image = task["input_image"]
    model_path = task["model_path"]
    output_image = task["output_image"]
    task_dir = os.path.dirname(output_image)

    if not os.path.exists(input_image):
        write_result(task_dir, "error", error=f"Input not found: {input_image}")
        return

    if not os.path.exists(model_path):
        write_result(task_dir, "error", error=f"Model not found: {model_path}")
        return

    if ort is None:
        write_result(task_dir, "error", error="onnxruntime not installed. Run: pip install onnxruntime")
        return

    if Image is None:
        write_result(task_dir, "error", error="Pillow not installed. Run: pip install Pillow")
        return

    try:
        input_img = Image.open(input_image).convert("RGB")
        orig_w, orig_h = input_img.size

        # Load model and detect layout
        session = ort.InferenceSession(
            model_path,
            providers=["CPUExecutionProvider"]
        )
        input_info = session.get_inputs()[0]
        input_shape = input_info.shape
        input_name = input_info.name
        output_name = session.get_outputs()[0].name

        # Detect NCHW ([1,3,H,W]) vs NHWC ([1,H,W,3])
        is_nchw = len(input_shape) == 4 and input_shape[1] in (1, 3) and (input_shape[3] or 0) > 3
        is_nhwc = len(input_shape) == 4 and input_shape[3] in (1, 3) and (input_shape[1] or 0) > 3
        unknown = not is_nchw and not is_nhwc

        if is_nhwc:
            target_h = int(input_shape[1])
            target_w = int(input_shape[2])
        else:
            target_w = int(input_shape[3])
            target_h = int(input_shape[2])

        resized = input_img.resize((target_w, target_h), Image.LANCZOS)
        np_img = np.array(resized, dtype=np.float32) / 255.0

        if is_nhwc:
            # NHWC: shape [1, H, W, 3] with raw uint8
            input_data = (np_img * 255).astype(np.uint8)
            ort_input = np.expand_dims(input_data, axis=0)
        else:
            # NCHW: shape [1, 3, H, W] with normalized float32
            mean = np.array([0.485, 0.456, 0.406], dtype=np.float32)
            std = np.array([0.229, 0.224, 0.225], dtype=np.float32)
            normalized = (np_img - mean) / std
            ort_input = np.expand_dims(np.transpose(normalized, (2, 0, 1)), axis=0).astype(np.float32)

        outputs = session.run([output_name], {input_name: ort_input})
        depth = outputs[0]

        # Parse output shape
        out_shape = depth.shape
        if len(out_shape) == 4:
            if out_shape[1] in (1, 3) and out_shape[3] > 3:
                out_h = int(out_shape[2])
                out_w = int(out_shape[3])
            else:
                out_h = int(out_shape[1])
                out_w = int(out_shape[2])
        elif len(out_shape) == 3:
            out_h, out_w = int(out_shape[1]), int(out_shape[2])
        elif len(out_shape) == 2:
            out_h, out_w = int(out_shape[0]), int(out_shape[1])
        elif len(out_shape) == 1:
            side = int(np.sqrt(out_shape[0]))
            out_h = side
            out_w = side
        else:
            write_result(task_dir, "error", error=f"Unexpected output shape: {out_shape}")
            return

        depth_flat = depth.flatten()[:out_w * out_h]
        depth_map = apply_inferno(depth_flat, out_w, out_h)

        # Scale back to original size
        depth_map = depth_map.resize((orig_w, orig_h), Image.LANCZOS)
        os.makedirs(os.path.dirname(output_image), exist_ok=True)
        depth_map.save(output_image, "PNG")

        write_result(task_dir, "ok", output_path=output_image)

    except Exception as e:
        import traceback
        error_msg = f"{type(e).__name__}: {e}\n{traceback.format_exc()}"
        write_result(task_dir, "error", error=error_msg)


def main():
    if len(sys.argv) < 2:
        print("Usage: depth_termux.py <task_json_path>", file=sys.stderr)
        sys.exit(1)

    task_path = sys.argv[1]
    if not os.path.exists(task_path):
        print(f"Task file not found: {task_path}", file=sys.stderr)
        sys.exit(1)

    with open(task_path) as f:
        task = json.load(f)

    run_inference(task)


if __name__ == "__main__":
    main()
