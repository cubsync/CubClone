#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
UI 配色自检：拦住「自绘页面用了系统默认配色的控件」和「对话框没跟应用主题」。

背景：页面全是代码建的 UI，底色按应用内的「跟随/深色/浅色」算，但 Activity 用的是
`Theme.DeviceDefault.NoActionBar`（跟系统深浅）。两者不一致时，任何不显式取色的控件
都会撞色：应用选深色 + 系统浅色 → 白底浅字。

硬规则（不通过就 build 失败）：
  1. 禁止 `new AlertDialog.Builder(...)`，一律 `Theme.dialog(context)`：
     对话框必须和应用用同一个深浅判断，否则里面自绘的控件（Theme.fg/sec）会撞底色。
  2. 禁止裸 `new Button(...)`：文字色由系统主题决定。要用 Button 就必须配
     Theme.primary() / Theme.disabledPrimary()；文字按钮用 TextView + Theme.chip()。
  3. `new TextView / CheckBox / RadioButton / Switch` 必须在附近显式取色
     （setTextColor 或 Theme.chip/check/primary/disabledPrimary）。

软提示（只打印，不失败）：EditText 的文字/提示色由主题控制，对话框已经跟应用主题，
保持一致即可，列出来供人工确认。

Theme.java 是实现，整体豁免。
"""
import os
import re
import sys

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'com', 'cubsync', 'cubclone')
SKIP = {'Theme.java'}
WINDOW = 26

COLORED = ('%s.setTextColor', 'Theme.chip(%s', 'Theme.check(%s',
           'Theme.primary(%s', 'Theme.disabledPrimary(%s')
NEED_COLOR = ('TextView', 'CheckBox', 'RadioButton', 'Switch', 'Button')
HINT_CLASSES = ('EditText',)


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

            if 'new AlertDialog.Builder(' in line:
                bad.append('%s:%d  对话框没跟应用主题：改用 Theme.dialog(context)\n    %s'
                           % (name, i + 1, s))
                continue

            m = re.search(r'(\w+)\s*=\s*new\s+(\w+)\(', line)
            if not m:
                continue
            var, cls = m.group(1), m.group(2)
            window = '\n'.join(lines[i:i + WINDOW])

            if cls == 'Button' and 'new Button(' in line:
                if not any(k % var in window for k in COLORED):
                    bad.append('%s:%d  裸 Button 没取色\n    %s' % (name, i + 1, s))
                continue

            if cls in HINT_CLASSES:
                hints.append('%s:%d  %s = new %s（跟随对话框主题）' % (name, i + 1, var, cls))
                continue

            if cls in NEED_COLOR:
                if not any(k % var in window for k in COLORED):
                    bad.append('%s:%d  %s = new %s 没取色：用 Theme 系列或 setTextColor\n    %s'
                               % (name, i + 1, var, cls, s))

    # EditText 只在自绘页面才需要提醒；对话框内是正常的，这里统一列出供人工看
    if bad:
        print('UI 配色自检失败（%d 处）：' % len(bad))
        for b in bad:
            print('  ' + b)
        return 1
    if hints:
        print('UI 配色自检通过（%d 个输入框跟随主题，正常）' % len(hints))
    else:
        print('UI 配色自检通过')
    return 0


if __name__ == '__main__':
    sys.exit(main())
