# 盒子跟随插件的 OCR 服务：FastAPI 包 RapidOCR（PP-OCR 模型）。
# 用 HearthstoneLogParser 的 .venv 运行（该环境已装 rapidocr_onnxruntime）：
#   .venv/Scripts/python.exe -m uvicorn main:app --host 127.0.0.1 --port 9233
# 由插件自动拉起（端口健康检查通过后使用），也可手动常驻。
import base64
import os

from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI()
_engine = None


def get_engine():
    global _engine
    if _engine is None:
        from rapidocr_onnxruntime import RapidOCR

        _engine = RapidOCR()
    return _engine


@app.on_event("startup")
def warmup():
    # 启动即加载模型（2~5 秒），避免首次请求超时
    get_engine()


class OcrRequest(BaseModel):
    image_path: str


@app.get("/health")
def health():
    return {"status": "ok"}


def to_b64(text: str) -> str:
    return base64.b64encode(text.encode("utf-8")).decode("ascii")


@app.post("/ocr")
def ocr(req: OcrRequest):
    path = req.image_path
    if not os.path.exists(path):
        return {"lines": [], "error": "file not found"}
    result, _elapsed = get_engine()(path)
    lines = []
    if result:
        for box, text, score in result:
            xs = [p[0] for p in box]
            ys = [p[1] for p in box]
            lines.append(
                {
                    # text 走 Base64 通道，规避 JSON 转义与编码问题
                    "b64": to_b64(text),
                    "cx": sum(xs) / 4.0,
                    "cy": sum(ys) / 4.0,
                    "score": float(score),
                }
            )
    return {"lines": lines}
