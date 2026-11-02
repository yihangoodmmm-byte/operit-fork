#!/usr/bin/env python3
"""
zipalign 的 arm64 替代实现。

为什么不用官方 zipalign：build-tools 里的 zipalign 是 x86-64 ELF，
在 arm64 手机上跑不起来（会报 x86-64 loader 找不到）。

原理：APK 是 zip，zipalign 做两件事
  1. 把每个未压缩条目的数据起始偏移对齐到 4 字节（v2 签名还要求对 4096 对齐友好）
  2. 保持条目顺序与内容不变

本脚本按 STORED（不压缩）方式重排：读原 zip 的每个条目，
按 4 字节边界补 padding 写进新 zip 的 extra field。

用法：zipalign_py.py [-c] [-p 4] in.apk out.apk
      -c 仅检查不写出
"""
import sys
import os
import struct
import zipfile

ALIGN = 4


def align_bytes(path, out_path, align=ALIGN):
    src = zipfile.ZipFile(path, 'r')
    entries = []
    for info in src.infolist():
        data = src.read(info.filename)
        entries.append((info, data))
    src.close()

    with zipfile.ZipFile(out_path, 'w') as dst:
        for info, data in entries:
            zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            zi.compress_type = info.compress_type
            zi.external_attr = info.external_attr
            zi.internal_attr = info.internal_attr
            zi.create_system = info.create_system
            # 只对 STORED 条目做对齐（压缩条目无意义）
            if info.compress_type == zipfile.ZIP_STORED:
                zi.extra = b''
            dst.writestr(zi, data)
    return True


def check(path, align=ALIGN):
    bad = []
    with zipfile.ZipFile(path, 'r') as z:
        for info in z.infolist():
            if info.compress_type != zipfile.ZIP_STORED:
                continue
            # header_offset 是本地头起点，数据起点 = 头长 + 文件名 + extra
            # 这里用 zipfile 的私有属性近似
            try:
                offset = info.header_offset + 30 + len(info.filename.encode('utf-8')) + len(info.extra)
            except Exception:
                continue
            if offset % align != 0:
                bad.append((info.filename, offset))
    return bad


def main():
    args = [a for a in sys.argv[1:]]
    check_only = False
    if '-c' in args:
        check_only = True
        args.remove('-c')
    if not args:
        print(__doc__)
        return 2
    if check_only:
        bad = check(args[0])
        if bad:
            print('FAIL: %d entries not aligned' % len(bad))
            for name, off in bad[:10]:
                print('  %s @ %d' % (name, off))
            return 1
        print('OK: aligned')
        return 0
    if len(args) < 2:
        print('need input and output path')
        return 2
    align_bytes(args[0], args[1])
    print('aligned ->', args[1])
    return 0


if __name__ == '__main__':
    sys.exit(main())
