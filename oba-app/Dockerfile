FROM transit-board-oba_app_base

COPY bundle-watcher-lib.sh /usr/local/bin/bundle-watcher-lib.sh
COPY bundle-watcher.sh /usr/local/bin/bundle-watcher.sh
RUN chmod +x /usr/local/bin/bundle-watcher.sh

COPY bundle-watcher.ini /etc/supervisor/conf.d/bundle-watcher.conf
