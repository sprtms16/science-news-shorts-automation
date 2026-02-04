FROM python:3.11-slim

WORKDIR /app

# Install docker-compose CLI
RUN apt-get update && apt-get install -y \
    docker-compose \
    && rm -rf /var/lib/apt/lists/*

# Install Python dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application
COPY autoscaler.py .

# Run autoscaler
CMD ["python", "-u", "autoscaler.py"]
