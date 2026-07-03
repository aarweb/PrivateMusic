"""Exporta el modelo 'vocals' de Open-Unmix (umxhq) a ONNX para el modo karaoke.

Publicar el resultado como asset de la release `models` del repo:
  gh release upload models umx_vocals.onnx --clobber
Requisitos: pip install torch torchaudio --index-url https://download.pytorch.org/whl/cpu
            pip install openunmix onnx onnxscript
"""
import torch
import openunmix

models = openunmix.umxhq_spec(targets=["vocals"], device="cpu", pretrained=True)
model = models["vocals"] if isinstance(models, dict) else models
model.eval()

# Entrada: magnitud del espectrograma (batch, canales, bins, frames) con n_fft=4096.
# Forma FIJA: el trazador hornea el nº de frames en los Reshape internos, así que
# la app procesa siempre bloques exactos de 1280 frames (~30 s) rellenando el último.
FRAMES = 1280
dummy = torch.rand(1, 2, 2049, FRAMES)
with torch.no_grad():
    out = model(dummy)
print("input", tuple(dummy.shape), "-> output", tuple(out.shape))
assert out.shape == dummy.shape, "output shape mismatch"

torch.onnx.export(
    model,
    dummy,
    "umx_vocals.onnx",
    input_names=["mix_mag"],
    output_names=["vocals_mag"],
    opset_version=17,
    dynamo=False,
)
print("EXPORT_OK")
