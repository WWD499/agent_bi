# -*- coding: utf-8 -*-
"""
本地 OCR 服务（PaddleOCR 2.7.3，当前 venv 已装版本）

启动：
    cd ocr-service
    python ocr_server.py
    # 或：uvicorn ocr_server:app --host 0.0.0.0 --port 8866

后端 bi-agent-platform 默认转发到 http://localhost:8866/ocr
（可在 application.yml 用 ocr.paddleocr-url 覆盖）

模型路径坑与解法：
    PaddleOCR 2.7.3 的模型缓存根是硬编码 BASE_DIR = ~/.paddleocr，
    不读任何环境变量；本机用户名含中文（C:\\Users\\黄卓行\\.paddleocr），
    而 PaddlePaddle C++ 推理引擎打不开含中文的路径 -> 报 NotFound。
    解法：已用 copy_models.py 把模型整目录复制到 ASCII 路径 D:/paddleocr_home，
    这里用 det_model_dir / rec_model_dir 显式指向它，彻底绕开中文 BASE_DIR。

返回格式（与 BiOcrServiceImpl.parse 对齐）：
    {
      "data": [
        {"bbox": [[x1,y1],[x2,y2],[x3,y3],[x4,y4]], "text": "识别文字", "confidence": 0.9921},
        ...
      ]
    }
"""
import os
import io
import time
import tempfile
import traceback

# === 关键：本机用户名含中文，PaddlePaddle C++ 引擎打不开 ~/.paddleocr 下的模型 ===
# 已用 copy_models.py 把模型复制到 ASCII 路径 D:/paddleocr_home，
# 下面用 det_model_dir / rec_model_dir 显式指向它，绕开硬编码的中文 BASE_DIR。
MODEL_HOME = os.environ.get("PADDLEOCR_MODEL_HOME", "D:/paddleocr_home")

import cv2
import numpy as np
import uvicorn
from fastapi import FastAPI, File, HTTPException, UploadFile
from paddleocr import PaddleOCR

app = FastAPI(title="PaddleOCR Service", version="2.7.3")

# 全局只初始化一次 OCR 引擎（首次加载模型较慢，约几秒到十几秒）
ocr_engine = None


def get_ocr():
    global ocr_engine
    if ocr_engine is None:
        # 2.7.3 兼容参数：显式指定 ASCII 路径的模型目录，避开中文 BASE_DIR
        ocr_engine = PaddleOCR(
            use_angle_cls=False,
            lang="ch",
            det_model_dir=os.path.join(MODEL_HOME, "whl", "det", "ch", "ch_PP-OCRv4_det_infer"),
            rec_model_dir=os.path.join(MODEL_HOME, "whl", "rec", "ch", "ch_PP-OCRv4_rec_infer"),
            show_log=False,
        )
    return ocr_engine


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/ocr")
async def ocr(file: UploadFile = File(...)):
    tmp_path = None
    try:
        # 先写临时文件（PaddleOCR 接受路径）
        suffix = os.path.splitext(file.filename or "img.jpg")[1] or ".jpg"
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as f:
            content = await file.read()
            f.write(content)
            tmp_path = f.name

        ocr = get_ocr()

        # PaddlePaddle C++ 引擎会在 stdout 输出诊断信息，临时重定向到 stderr
        _fd = os.dup(1)
        os.dup2(os.dup(2), 1)
        try:
            result = ocr.ocr(tmp_path)
        finally:
            os.dup2(_fd, 1)
            os.close(_fd)

        data = []
        if result and len(result) > 0:
            ocr_result = result[0]
            texts, scores, polys = [], [], []

            # 方式1：3.x OCRResult 对象（推荐）
            if hasattr(ocr_result, "json"):
                res = ocr_result.json.get("res", {})
                texts = res.get("rec_texts", [])
                scores = res.get("rec_scores", [])
                polys = res.get("rec_polys") or res.get("boxes") or []

            # 方式2：向后兼容 2.x 格式（list of [box, (text, conf)]）
            elif isinstance(ocr_result, list):
                for line in ocr_result:
                    if isinstance(line, (list, tuple)) and len(line) >= 2:
                        td = line[1]
                        if isinstance(td, (list, tuple)):
                            texts.append(td[0])
                            scores.append(td[1] if len(td) > 1 else 0)
                        elif isinstance(td, str):
                            texts.append(td)
                            scores.append(0)
                        polys.append(line[0] if line else [])

            for i, t in enumerate(texts):
                conf = scores[i] if i < len(scores) else 0
                box = polys[i] if i < len(polys) else []
                data.append({
                    "text": t,
                    "confidence": float(conf) if conf else 0,
                    "bbox": box,
                })

        return {"data": data}
    except Exception as e:
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        if tmp_path and os.path.exists(tmp_path):
            try:
                os.remove(tmp_path)
            except Exception:
                pass


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8866)
