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
  4. 禁止硬编码 0xAARRGGBB：颜色只能从 Theme 取（Theme.java 除外，0x00000000 透明放过）。
     事故：11-24 RootActivity/各页各画一套色值，选浅色主题反而画深色。
  5. 禁止自己算深浅：读 theme 偏好后直接 return true/false 或看 uiMode 一律拦。
     按主题选图标/文案的三元表达式是正当用法。
     事故：同一天 RootActivity.isNight() 把 1=深 / 2=浅 写反，注释警告过还是犯。
  6. `pm install` 必须带 `--user <n>`：否则装进全部分身，LSPosed 对每个分身报未激活。
     事故：11-24 解锁模块装进 7 个用户。
  7. 代码里禁写上游客制厂商名，统一说「系统自带分身」（公开仓铁律）。

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

COLOR_LITERAL = re.compile(r'0x[0-9A-Fa-f]{8}\b')
COLOR_OK = {'0x00000000'}
THEME_READ = re.compile(r'getInt\(\s*"theme"')
NIGHT_CALC = re.compile(
    r'UI_MODE_NIGHT_MASK'
    r'|(?:THEME_DARK|THEME_LIGHT|t\s*==\s*[12]).{0,40}return\s+(?:true|false)')
PM_INSTALL = re.compile(r'"pm install\b[^"]*"')
VENDOR = re.compile(r'Flyme|Meizu|\bmoto\b|摩托罗拉|MIUI|HyperOS|ColorOS|OriginOS|OneUI')


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

            code = line.split('//')[0]

            # 4 硬编码颜色
            m = COLOR_LITERAL.search(code)
            if m and m.group(0) not in COLOR_OK:
                bad.append('%s:%d  硬编码颜色 %s：改用 Theme.xxx()\n    %s'
                           % (name, i + 1, m.group(0), s))

            # 5 自己算深浅（只看后 2 行，避免误伤按主题选图标/文案的三元）
            if THEME_READ.search(code):
                window = '\n'.join(lines[i:i + 3])
                if NIGHT_CALC.search(window):
                    bad.append('%s:%d  自己算深浅：改用 Theme.isNight()/isBlack()\n    %s'
                               % (name, i + 1, s))

            # 6 pm install 不带 --user 会装进所有分身
            for cmd in PM_INSTALL.findall(code):
                if '--user' not in cmd:
                    bad.append('%s:%d  pm install 缺 --user 0（会装进每个分身）\n    %s'
                               % (name, i + 1, s))

            # 7 公开仓禁写上游客制厂商名
            vm = VENDOR.search(code)
            if vm:
                bad.append('%s:%d  写了厂商名 %s：统一说「系统自带分身」\n    %s'
                           % (name, i + 1, vm.group(0), s))

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
