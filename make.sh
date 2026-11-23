#!/bin/sh

# usage: sh make.sh cli|srv
appname=${1}
case ${appname} in
  cli | srv) ;;
  *)
    echo "unknown target: ${appname} (expect cli|srv)"
    exit 1
    ;;
esac

outdir="build"
[ -d ${outdir} ] || mkdir -p ${outdir}

outname="agrmt-${appname}"
outpath="${outdir}/${outname}"

last_md5=$(md5sum ${outdir}/${outname} 2>/dev/null | awk '{print $1}')

export GOPROXY="https://goproxy.cn,direct"
go mod tidy
go mod vendor
CGO_ENABLED=0 go build -ldflags "-s -w" -v -o "${outpath}" "./${appname}" || {
    echo "============build failed: not changed ========="
    exit 1
}

new_md5=$(md5sum "${outpath}" | awk '{print $1}')
if [ -n "${last_md5}" ] && [ "${new_md5}" = "${last_md5}" ]; then
    echo "============build failed: not changed ========="
    echo "last_md5=${last_md5}"
    echo "new_md5=${new_md5}"
    exit 1
fi

echo "============build done========="
ls -l "${outpath}"
echo "last_md5=${last_md5}"
echo "new_md5=${new_md5}"
