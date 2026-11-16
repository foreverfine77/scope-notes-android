"""Device regression: a held profile moves live, persists, and stays in the app.

Uses existing profiles only; restores their order after the check.
"""
import pathlib
import re
import subprocess
import json
import sys
import time
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
ADB = ROOT / '.android-tools/platform-tools/adb.exe'
OUT = ROOT / '.android-tools/video-inspection/review-1116'
OUT.mkdir(parents=True, exist_ok=True)
sys.stdout.reconfigure(encoding='utf-8')

def adb(*args):
    return subprocess.check_output([str(ADB), *args], text=True, encoding='utf-8', errors='replace')

def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/scope-order.xml')
    adb('pull', '/sdcard/scope-order.xml', str(OUT / 'order.xml'))
    return list(ET.parse(OUT / 'order.xml').iter('node'))

def bounds(node):
    return list(map(int, re.findall(r'\d+', node.attrib['bounds'])))

def tap(node):
    x1, y1, x2, y2 = bounds(node)
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

def find(label, items):
    return next((n for n in items if label in (n.get('text'), n.get('content-desc'))), None)

def open_manager():
    for attempt in range(8):
        items = nodes()
        if find('长按拖动排序', items) is not None:
            return items
        target = None
        for label in ('继续', '设置', '管理护耳档案'):
            item = find(label, items)
            if item is not None and item.get('clickable') == 'true':
                target = item
                break
        if target is not None:
            tap(target)
        else:
            adb('shell', 'input', 'swipe', '700', '800', '700', '2400', '400')
    raise AssertionError('Could not open profile manager')

def handles(items):
    return [n for n in items if n.get('content-desc') == '长按拖动排序']

def order(items):
    return [n.get('content-desc') for n in items if n.get('resource-id') == 'dev.earclean.preview:id/profile_name'] or [
        n.get('text') for n in items if n.get('class') == 'android.widget.Button'
        and n.get('text') and n.get('text') not in ('＋ 添加使用人',)]

def drag(first, second, screenshot=None):
    a, b = bounds(first), bounds(second)
    x, y, end = (a[0]+a[2])//2, (a[1]+a[3])//2, (b[1]+b[3])//2
    commands = [f'input motionevent DOWN {x} {y}', 'sleep 0.35']
    commands += [f'input motionevent MOVE {x} {int(y+(end-y)*i/8)}; sleep 0.04' for i in range(1,9)]
    if screenshot:
        commands += ['sleep 0.2', f'screencap -p /sdcard/{screenshot}.png']
    commands += [f'input motionevent UP {x} {end}']
    adb('shell', '; '.join(commands))
    if screenshot:
        adb('pull', f'/sdcard/{screenshot}.png', str(OUT / f'{screenshot}.png'))
    time.sleep(.3)

items = open_manager()
hs = handles(items)
assert len(hs) >= 2, 'Need at least two existing profiles visible; no profiles were created.'
before = order(items)
(OUT / 'order-before.json').write_text(json.dumps(before, ensure_ascii=False), encoding='utf-8')
print('Testing', len(before), 'existing profiles; original order saved.')
try:
    drag(hs[0], hs[-1], 'scope-order-last-held')
    after_items = nodes()
    assert all(n.get('package') == 'dev.earclean.preview' for n in after_items), 'External recognizer opened'
    assert order(after_items) == before[1:] + before[:1], 'First row did not reach final slot'
    print('PASS: first row inserted at final slot, stayed in app.')
    adb('shell', 'input', 'keyevent', 'BACK')
    after_items = open_manager()
    assert order(after_items) == before[1:] + before[:1], 'Order did not persist after reopening'
    print('PASS: order persisted after reopening.')
    hs = handles(after_items)
    drag(hs[-1], hs[0], 'scope-order-first-held')
    assert order(nodes()) == before, 'Final row did not reach first slot'
    print('PASS: last row inserted at first slot.')
finally:
    current_items = nodes()
    current = order(current_items)
    if current and set(current) == set(before):
        for index, name in enumerate(before):
            if current[index] == name:
                continue
            hs = handles(current_items)
            drag(hs[current.index(name)], hs[index])
            current_items = nodes()
            current = order(current_items)
        assert current == before, 'Could not restore original profile order'
        print('PASS: original profile order restored.')
    else:
        print('ATTENTION: could not inspect profile order for restoration.')
