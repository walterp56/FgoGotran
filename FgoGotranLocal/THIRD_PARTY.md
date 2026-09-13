# Third-party components

FgoGotranLocal uses Python packages listed in `requirements.txt` and controls a separately downloaded llama.cpp runtime.

- llama.cpp: <https://github.com/ggml-org/llama.cpp>
- Gradio: <https://github.com/gradio-app/gradio>
- FastAPI: <https://github.com/fastapi/fastapi>
- Python 3.13.15 private runtime: <https://www.python.org/downloads/release/python-31315/>

llama.cpp, CUDA runtime files, Python, and GGUF models are not redistributed in this folder. The first-run launcher can download Python and llama.cpp from the sources above after confirmation and stores them only in ignored local directories. It never downloads or manages GGUF models; users obtain those separately and remain responsible for reviewing and following their upstream licenses.
