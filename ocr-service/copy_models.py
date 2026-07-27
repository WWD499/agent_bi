# -*- coding: utf-8 -*-
"""把用户目录下的 .paddleocr (中文路径) 复制到 ASCII 路径 D:/paddleocr_home。
PaddlePaddle 2.7.3 的 C++ 推理引擎打不开含中文的路径，复制后由 ocr_server.py
用 det_model_dir/rec_model_dir 显式指向 ASCII 目录即可绕过。
源路径用 expanduser('~') 解析，脚本本身不出现任何中文硬编码。
"""
import os
import shutil

src = os.path.expanduser("~/.paddleocr")
dst = "D:/paddleocr_home"

if not os.path.isdir(src):
    raise SystemExit("SOURCE_NOT_FOUND: " + src)

if os.path.exists(dst):
    shutil.rmtree(dst)

shutil.copytree(src, dst)

det = os.path.join(dst, "whl", "det", "ch", "ch_PP-OCRv4_det_infer", "inference.pdmodel")
rec = os.path.join(dst, "whl", "rec", "ch", "ch_PP-OCRv4_rec_infer", "inference.pdmodel")
print("COPIED", src, "->", dst)
print("det_model_ok:", os.path.exists(det))
print("rec_model_ok:", os.path.exists(rec))
