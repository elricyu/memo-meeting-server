#!/usr/bin/env bash
# Ubuntu 22.04 packages unpacked locally; no sudo or system package changes.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p runtime/ffmpeg-packages runtime/ffmpeg-root
cd runtime/ffmpeg-packages
apt-get -o Acquire::ForceIPv4=true download ffmpeg libavcodec58 libavformat58 libavutil56 libswresample3 \
  libswscale5 libavfilter7 libavdevice58 libpostproc55 libdav1d5 libzvbi0 \
  libzvbi-common libsnappy1v5 libaom3 libcodec2-1.0 libgsm1 libshine3 \
  libx264-163 libx265-199 libxvidcore4 libmfx1 libgme0 libopenmpt0 \
  libchromaprint1 libbluray2 librabbitmq4 libsrt1.4-gnutls libssh-gcrypt-4 \
  libzmq5 ocl-icd-libopencl1 libudfread0 libpgm-5.3-0 libnorm1 \
  libopenal1 libopenal-data libdc1394-25 libsdl2-2.0-0 libsndio7.0 \
  libpocketsphinx3 libsphinxbase3 libbs2b0 liblilv-0-0 librubberband2 \
  libmysofa1 libflite1 libass9 libvidstab1.1 libzimg2 libsord-0-0 \
  libsratom-0-0 libserd-0-0 liblapack3 libblas3 libraw1394-11 libdecor-0-0 libgfortran5 libquadmath0
for package in *.deb; do dpkg-deb -x "$package" ../ffmpeg-root; done
cd ../ffmpeg-root/usr/lib/x86_64-linux-gnu
ln -sfn blas/libblas.so.3 libblas.so.3
ln -sfn lapack/liblapack.so.3 liblapack.so.3
