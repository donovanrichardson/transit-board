FROM python:3.12-alpine

RUN pip install --no-cache-dir pymysql

WORKDIR /app
COPY updater.py .
CMD ["python", "updater.py"]
