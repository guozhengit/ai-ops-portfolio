FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY chaos_demo.py chaos_ui.py .
RUN mkdir -p /data/chaos-logs
ENV CHAOS_DATA_DIR=/data/chaos-logs
EXPOSE 9400
CMD ["python", "chaos_demo.py"]
