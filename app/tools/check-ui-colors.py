#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
UI 配色自检：拦住「自绘页面用了系统默认配色的控件」。

背景色是本应用自己按深浅色画的，系统主题（Theme.DeviceDefault）不知道这件事，
它给的 Button 默认色永远按系统深浅走。于是背景一深、按钮文字还是黑字，
就成了看不见的按钮——这类问题反复出现过（数据备份标题、批量删除页按钮）。

硬规则（不通过就 build 失败）：
  禁止裸 new Button(...)：文字色由系统主题决定。要用 Button 就必须紧接着
  Theme.primary() / Theme.disabledPrimary()；文字按钮请用 TextView + Theme.chip()。
  Theme.java 自己是实现，豁免。

软提示（只打印，不失败）：AlertDialog 里的 CheckBox / EditText 走系统对话框主题，
天然跟着系统深浅，不需要应用色板——列出来只是提醒别在自绘页面里这么写。
"""
import os
import sys

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'com', 'cubsync', 'cubclone')
SKIP = {'Theme.java'}
BUTTON_OK = ('Theme.primary(', 'Theme.disabledPrimary(')


def main():
    bad, hints = [], []
    for name in sorted(os.listdir(SRC)):
        if not name.endswith('.java') or name in SKIP:
            continue
        lines = open(os.path.join(SRC, name), encoding='utf-8').read().split('\n')
        for i, line in enumerate(lines):
            s = line.strip()
            if s.startswith('*') or s.startswith('//'):
                continue
            if 'new Button(' in line:
                window = '\n'.join(lines[i:i + 5])
                if not any(k in window for k in BUTTON_OK):
                    bad.append('%s:%d  裸 Button：改用 TextView + Theme.chip()，'
                               '或 Button + Theme.primary()\n    %s' % (name, i + 1, s))
            if 'new CheckBox(' in line or 'new EditText(' in line:
                hints.append('%s:%d  %s' % (name, i + 1, s))

    if bad:
        print('UI 配色自检失败（%d 处）：' % len(bad))
        for b in bad:
            print('  ' + b)
        return 1
    if hints:
        print('UI 配色自检通过（%d 个对话框内控件走系统主题，正常）' % len(hints))
    else:
        print('UI 配色自检通过')
    return 0


if __name__ == '__main__':
    sys.exit(main())
