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
  8. 布局里的 TextView 必须能跟到主题色：写 `android:textColor`，或者有 id 且在
     Java 里 findViewById 到某个变量后 setTextColor。只写 id 不取色不算。
     漏洞记录（11-28）：规则 3 只扫 `new TextView(...)`，XML 布局是盲区 —— 「功能开关」
     卡片的 7 个 TextView 全用系统默认色，应用切深色而系统浅色时看着像没生效。
  9. 带 `@drawable/bg_card` 的卡片必须有 id，并在 applyChrome 里 `setTint(Theme.card(...))`。
     资源限定符跟的是系统深浅，应用内的深/浅/纯黑是另一套 —— 不 tint 就永远有一半场景配错。

软提示（只打印，不失败）：EditText 的文字/提示色由主题控制，对话框已经跟应用主题，
保持一致即可，列出来供人工确认。

Theme.java 是实现，整体豁免。
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, '..', 'src', 'com', 'cubsync', 'cubclone')
LAYOUT = os.path.join(HERE, '..', 'res', 'layout')
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


def layout_colored_ids():
    """布局里哪些 id 真被取过色：变量绑到 id 且该变量有 setTextColor，
    或 id 出现在 new int[]{...} / TextView[]{...} 且随后有 setTextColor，
    或 findViewById(R.id.x).setTextColor。"""
    binds, colored_vars, extras = {}, set(), set()
    for name in sorted(os.listdir(SRC)):
        if not name.endswith('.java') or name in SKIP:
            continue
        src = open(os.path.join(SRC, name), encoding='utf-8').read()
        for m in re.finditer(r'(\w+)\s*=\s*(?:\([^)]*\)\s*)?(?:\w+\.)?findViewById\(\s*R\.id\.(\w+)\s*\)', src):
            binds.setdefault(m.group(2), set()).add(m.group(1))
        for m in re.finditer(r'(\w+)\.setTextColor\s*\(', src):
            colored_vars.add(m.group(1))
        for m in re.finditer(r'findViewById\(\s*R\.id\.(\w+)\s*\)\s*\)?\s*\.setTextColor', src):
            extras.add(m.group(1))
        # 取色数组四种写法：new int[]{..}、TextView[] a = {..}、= new TextView[]{..}、for (.. : new TextView[]{..})
        for pat in (r'new int\[\]\s*\{([^}]*)\}',
                    r'(?:TextView|View)\[\]\s*\w+\s*=\s*\{([^}]*)\}',
                    r'(?:TextView|View)\[\]\s*\w+\s*=\s*new\s+(?:TextView|View)\[\]\s*\{([^}]*)\}',
                    r':\s*new\s+(?:TextView|View)\[\]\s*\{([^}]*)\}'):
            for m in re.finditer(pat, src):
                if '.setTextColor' not in src[m.end():m.end() + 900]:
                    continue
                body = m.group(1)
                extras.update(re.findall(r'R\.id\.(\w+)', body))
                colored_vars.update(re.findall(r'[a-zA-Z_]\w*', body))
    return {i for i, vs in binds.items() if vs & colored_vars} | extras


def tinted_ids():
    """哪些 id 的卡片底色被代码 tint 过（bg_card 必须跟着应用深浅，不能只靠资源限定符）"""
    out = set()
    for name in sorted(os.listdir(SRC)):
        if not name.endswith('.java') or name in SKIP:
            continue
        src = open(os.path.join(SRC, name), encoding='utf-8').read()
        for m in re.finditer(r'new int\[\]\s*\{([^}]*)\}', src):
            if '.setTint' in src[m.end():m.end() + 800]:
                out.update(re.findall(r'R\.id\.(\w+)', m.group(1)))
        for m in re.finditer(r'(\w+)\s*=\s*(?:\([^)]*\)\s*)?(?:\w+\.)?findViewById\(\s*R\.id\.(\w+)\s*\)', src):
            var, rid = m.group(1), m.group(2)
            if re.search(r'\b' + var + r'\s*\.\s*getBackground\(\)\s*\.\s*setTint', src):
                out.add(rid)
    return out


def check_cards(bad, tinted):
    """带 bg_card 背景的布局必须有 id，且该 id 在代码里 setTint 过"""
    n = 0
    for name in sorted(os.listdir(LAYOUT)):
        if not name.endswith('.xml'):
            continue
        src = open(os.path.join(LAYOUT, name), encoding='utf-8').read()
        for m in re.finditer(r'@drawable/bg_card', src):
            head = src[max(0, m.start() - 600):m.start()]
            ids = re.findall(r'android:id="@\+id/(\w+)"', head)
            line = src[:m.start()].count('\n') + 1
            if not ids:
                bad.append('%s:%d  bg_card 卡片没 id：底色只能跟系统深浅，应用换主题不跟随'
                           % (name, line))
            elif ids[-1] not in tinted:
                bad.append('%s:%d  bg_card 卡片 id=%s 没在代码里 setTint(Theme.card())'
                           % (name, line, ids[-1]))
            else:
                n += 1
    return n


TEXTVIEW = re.compile(r'<TextView\b(.*?)(?:/>|>)', re.S)
DECOR = re.compile(r'^[^\w一-鿿]{1,3}$')


def check_layouts(bad, ok_ids):
    n = 0
    for name in sorted(os.listdir(LAYOUT)):
        if not name.endswith('.xml'):
            continue
        src = open(os.path.join(LAYOUT, name), encoding='utf-8').read()
        for m in TEXTVIEW.finditer(src):
            blk = m.group(1)
            if 'android:textColor' in blk:
                continue
            tm = re.search(r'android:text="([^"]*)"', blk)
            text = tm.group(1) if tm else ''
            if text and not text.startswith('@string/') and DECOR.match(text):
                continue          # 「›」这类装饰箭头
            line = src[:m.start()].count('\n') + 1
            im = re.search(r'android:id="@\+id/(\w+)"', blk)
            if not im:
                bad.append('%s:%d  布局 TextView 没 id 也没 textColor：拿不到主题色\n    %s'
                           % (name, line, text or '(无文字)'))
            elif im.group(1) not in ok_ids:
                bad.append('%s:%d  TextView id=%s 没在代码里 setTextColor\n    %s'
                           % (name, line, im.group(1), text or '(无文字)'))
            else:
                n += 1
    return n


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

    n = check_layouts(bad, layout_colored_ids())
    nc = check_cards(bad, tinted_ids())

    # EditText 只在自绘页面才需要提醒；对话框内是正常的，这里统一列出供人工看
    if bad:
        print('UI 配色自检失败（%d 处）：' % len(bad))
        for b in bad:
            print('  ' + b)
        return 1
    if hints:
        print('UI 配色自检通过（%d 个布局文本走代码取色、%d 张卡片跟随主题、%d 个输入框跟随主题）'
              % (n, nc, len(hints)))
    else:
        print('UI 配色自检通过（%d 个布局文本走代码取色、%d 张卡片跟随主题）' % (n, nc))
    return 0


if __name__ == '__main__':
    sys.exit(main())
