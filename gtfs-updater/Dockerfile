FROM python:3.12-alpine

RUN apk add --no-cache docker-cli docker-cli-compose

RUN pip install --no-cache-dir pymysql

WORKDIR /app
COPY updater.py .
CMD ["python", "updater.py"]
