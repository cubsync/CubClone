# -*- coding: utf-8 -*-
"""
中英资源自检（构建前跑，见 app/build.sh 的 [0/7]）。

硬门禁：
  1) values/strings.xml 与 values-en/strings.xml 的键集合必须完全一致；
  2) values-en 里不许有中文（lang_zh 是语言选项自身，白名单）。
提示（不阻塞）：
  3) src 里的 Java 字符串字面量含中文——新功能忘了走 R.string 就会在这里露出来。

用法：python tools/check-i18n.py [--strict]
  --strict 时第 3 条也算失败（发版前建议加）。
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

APP = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ZH_FILE = os.path.join(APP, 'res', 'values', 'strings.xml')
EN_FILE = os.path.join(APP, 'res', 'values-en', 'strings.xml')
ZH = re.compile(r'[\u4e00-\u9fa5]')
EN_WHITELIST = {'lang_zh'}
# 刷 Magisk 模块的脚本正文只在 root 用户刷入时看，不参与应用界面语言
HARD_OK_FILES = {'CloneStore.java'}


def strings(path):
    root = ET.parse(path).getroot()
    out, dup = {}, []
    for c in root:
        if c.tag != 'string':
            continue
        n = c.get('name')
        if n in out:
            dup.append(n)
        out[n] = ''.join(c.itertext())
    return out, dup


def strip_comment(line):
    i = line.find('//')
    line = line if i < 0 else line[:i]
    s = line.lstrip()
    if s.startswith('*') or s.startswith('/*') or s.startswith('//'):
        return ''
    return line


def main():
    strict = '--strict' in sys.argv
    zh, dup_zh = strings(ZH_FILE)
    en, dup_en = strings(EN_FILE)
    bad = []

    if dup_zh:
        bad.append('zh 重复键: ' + ', '.join(sorted(set(dup_zh))))
    if dup_en:
        bad.append('en 重复键: ' + ', '.join(sorted(set(dup_en))))

    miss = sorted(set(zh) - set(en))
    extra = sorted(set(en) - set(zh))
    if miss:
        bad.append('en 缺 %d 个键: %s' % (len(miss), ', '.join(miss[:12])))
    if extra:
        bad.append('en 多 %d 个键: %s' % (len(extra), ', '.join(extra[:12])))

    zh_in_en = [k for k, v in en.items() if ZH.search(v) and k not in EN_WHITELIST]
    if zh_in_en:
        bad.append('en 里还是中文 %d 条: %s' % (len(zh_in_en), ', '.join(zh_in_en[:12])))

    # 提示：Java 里未资源化的中文
    hard = []
    for f in glob.glob(os.path.join(APP, 'src', '**', '*.java'), recursive=True):
        name = os.path.basename(f)
        if name in HARD_OK_FILES:
            continue
        with open(f, encoding='utf-8') as fh:
            for i, line in enumerate(fh, 1):
                code = strip_comment(line)
                for lit in re.findall(r'"([^"\\]*(?:\\.[^"\\]*)*)"', code):
                    if ZH.search(lit):
                        hard.append('%s:%d  "%s"' % (name, i, lit[:36]))

    print('  中英键 %d / %d' % (len(zh), len(en)))
    if bad:
        for b in bad:
            print('  [FAIL] ' + b)
    if hard:
        tag = 'FAIL' if strict else 'WARN'
        print('  [%s] Java 里仍有 %d 处中文未走 R.string：' % (tag, len(hard)))
        for h in hard[:20]:
            print('     ' + h)
        if len(hard) > 20:
            print('     … 另有 %d 处' % (len(hard) - 20))
        if strict:
            bad.append('Java 硬编码中文 %d 处' % len(hard))

    if bad:
        print('  中英资源自检不通过')
        return 1
    print('  中英资源自检通过')
    return 0


if __name__ == '__main__':
    sys.exit(main())
