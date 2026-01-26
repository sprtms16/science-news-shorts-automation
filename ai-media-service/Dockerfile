FROM python:3.10-slim

WORKDIR /app

# System dependencies
RUN apt-get update && apt-get install -y \
    libgl1 \
    libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

# Python dependencies
COPY requirements.txt .
# Install CPU-only torch to reduce size and ensure compatibility on Mac ARM64
RUN pip install --no-cache-dir torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cpu
RUN pip install --no-cache-dir -r requirements.txt

# App Source
COPY app.py .

# Output Volume
RUN mkdir -p /app/output

EXPOSE 8000
CMD ["python", "app.py"]
