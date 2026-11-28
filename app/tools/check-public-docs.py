#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
公开文档自检：仓库里的 md 会被所有人看到，只写「规则与事实」。

不进公开文档的东西（每条都踩过）：
  1. 测试设备与代号（G7 / 8A / esp / OP12 …）、序列号、机型 —— 跟项目无关，还暴露测试环境。
  2. 厂商名（含 ROM 名）—— 统一说「系统自带分身」「部分 ROM」「内核带 sdcardfs 的机型」。
  3. 第三方应用名 —— 写「第三方支付类应用」「聊天类应用」，不写具体是谁。
  4. 内网地址、uid 样本、shell 命令回显 —— 属于现场，不属于文档。
  5. 「实测」「真机」「我这边」这类过程叙述 —— 文档写结论，验证过程留在提交与本地记录。
  6. 人名与对话引用。

例外：docs/reference/ 是本地资料（.gitignore 挡住），不在检查范围。
"""
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))

SKIP_DIRS = {'docs/reference', 'build', 'dist', '.git', '.gradle'}

RULES = [
    ('测试设备/代号', re.compile(r'\bG7\b|\bdsg7\b|\brm8a\b|\besp\b|\bOP12\b|\bK40\b|'
                             r'PJD110|ZY22FCWMK9|72021458|S00572K022726|'
                             r'2231d5e90807|227d97360807')),
    ('厂商名', re.compile(r'魅族|Flyme|moto|摩托罗拉|格力|大松|红米|Redmi|小米|一加|OnePlus|'
                       r'OPPO|vivo|华为|三星|Samsung|ColorOS|MIUI|HyperOS|OriginOS|'
                       r'OneUI|OxygenOS|Oplus')),
    ('第三方应用名', re.compile(r'微信|支付宝|淘宝|抖音|钉钉|腾讯|芝麻粒|XRadiant|testpick|Aicy')),
    ('人名/对话', re.compile(r'川|胡兴川|我这边|你说|他说')),
    ('内网地址', re.compile(r'\b10\.\d+\.\d+\.\d+\b|\b192\.168\.\d+\.\d+\b')),
    ('过程叙述', re.compile(r'实测|真机')),
    ('uid 样本', re.compile(r'\bu1[0-9]\b')),
]


def tracked_docs():
    """只查 git 跟踪的 md：本地资料被 .gitignore 挡住，不算公开文档"""
    try:
        out = subprocess.check_output(['git', 'ls-files', '-z', '--', '*.md'], cwd=ROOT)
        return sorted(os.path.join(ROOT, f)
                      for f in out.decode('utf-8').split('\0') if f)
    except Exception:
        pass
    out = []
    for base, dirs, files in os.walk(ROOT):
        rel = os.path.relpath(base, ROOT).replace('\\', '/')
        if rel == '.':
            rel = ''
        if any(rel == d or rel.startswith(d + '/') for d in SKIP_DIRS):
            dirs[:] = []
            continue
        for f in files:
            if f.endswith('.md'):
                out.append(os.path.join(base, f))
    return sorted(out)


def main():
    bad = []
    n = 0
    for path in tracked_docs():
        rel = os.path.relpath(path, ROOT).replace('\\', '/')
        with open(path, encoding='utf-8') as fh:
            for i, line in enumerate(fh, 1):
                n += 1
                for name, rx in RULES:
                    m = rx.search(line)
                    if m:
                        bad.append('%s:%d  写了%s「%s」\n    %s'
                                   % (rel, i, name, m.group(0), line.strip()[:110]))
    if bad:
        print('公开文档自检失败（%d 处）：' % len(bad))
        for b in bad:
            print('  ' + b)
        return 1
    print('公开文档自检通过（%d 行）' % n)
    return 0


if __name__ == '__main__':
    sys.exit(main())
