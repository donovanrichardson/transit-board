FROM transit-board-oba_app_base

RUN grep -qF 'pattern="%h %l %u %t &quot;%r&quot; %s %b"' /usr/local/tomcat/conf/server.xml \
    || exit 1 && \
    sed -i 's|pattern="%h %l %u %t \&quot;%r\&quot; %s %b"|pattern="%h %l %u %t \&quot;%m %U %H\&quot; %s %b"|' \
        /usr/local/tomcat/conf/server.xml && \
    grep -qF 'pattern="%h %l %u %t &quot;%m %U %H&quot; %s %b"' /usr/local/tomcat/conf/server.xml \
    || exit 1

COPY bundle-watcher-lib.sh /usr/local/bin/bundle-watcher-lib.sh
COPY bundle-watcher.sh /usr/local/bin/bundle-watcher.sh
RUN chmod +x /usr/local/bin/bundle-watcher.sh

COPY bundle-watcher.ini /etc/supervisor/conf.d/bundle-watcher.conf
