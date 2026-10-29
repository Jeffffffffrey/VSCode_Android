#!/system/bin/sh
# 由 BootstrapScriptBuilder 生成。
# 职责：用 proot 在用户态伪造根文件系统，长时间阻塞运行 code-server。
set -u

PREFIX="{{PREFIX}}"
BIN="{{BIN}}"
TMP="{{TMP}}"
ROOTFS="{{ROOTFS}}"

# 这些变量是 proot 自身和宿主 shell 需要的
export HOME="{{HOME}}"
export TMPDIR="$TMP"
export PREFIX
export TERMUX_PREFIX="$PREFIX"
export PATH="$BIN:/system/bin:/system/xbin:/vendor/bin"
export PROOT_LOADER="$BIN/loader"
export PROOT_TMP_DIR="$TMP"
export LD_LIBRARY_PATH="$BIN"
export TERM=xterm-256color
export LANG=C.UTF-8

mkdir -p "$TMP"

echo "::LOG::启动 code-server（http://127.0.0.1:{{PORT}}）"

exec "$BIN/proot" \
  --rootfs="$ROOTFS" \
  --cwd=/root \
  --change-id=0:0 \
  --bind=/dev \
  --bind=/proc \
  --bind=/sys \
  --bind="$ROOTFS/tmp:/dev/shm" \
  --link2symlink \
  --sysvipc \
  --kernel-release=6.2.1-PRoot-Distro \
  {{EXTRA_BINDS}} \
  /bin/bash -c "unset LD_LIBRARY_PATH PROOT_LOADER PROOT_TMP_DIR TERMUX_PREFIX PREFIX; \
    export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; \
    export HOME=/root; \
    export TMPDIR=/tmp; \
    export TERM=xterm-256color; \
    export LANG=C.UTF-8; \
    cd /root && exec {{CS_GUEST_PATH}}/bin/code-server \
      --bind-addr 127.0.0.1:{{PORT}} {{AUTH_ARGS}} \
      --disable-telemetry --disable-update-check"
