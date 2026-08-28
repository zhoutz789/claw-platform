#!/bin/zsh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
export JAVA_TOOL_OPTIONS="-Dserver.port=49165 -Dclaw.iot.emqx.enabled=true -Dclaw.iot.emqx.username=claw-server -Dclaw.iot.emqx.password=claw-server-secret -Dclaw.iot.emqx.server-secret=claw-server-secret -Dclaw.security.dev-open-access=true -Dnet.bytebuddy.experimental=true"
cd /Users/zhoutianzhi/WorkBuddy/Claw/claw-platform/backend
exec mvn -o spring-boot:run
