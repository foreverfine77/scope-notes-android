"""Rebuild the review snapshot from the Android sources. Standard library only."""

from pathlib import Path
import csv
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET

PACKAGE = Path(__file__).resolve().parents[1]
ROOT = PACKAGE.parent
JAVA = ROOT / "android-preview/src/dev/earclean/preview"


def read_strings(locale):
    result = {}
    for node in ET.parse(ROOT / "android-preview/res" / locale / "strings.xml").getroot():
        value = "".join(node.itertext())
        if value.startswith('"') and value.endswith('"'):
            value = value[1:-1]
        result[node.attrib["name"]] = value.replace("\\n", "\n")
    return result


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


# page ID, title, current source method, component ownership, redesign treatment
PAGE_ROWS = [
    ("capture", "取景页", "PreviewActivity.onCreate", "App 自绘", "固定操作区，画面占位按真实比例缩放"),
    ("gallery", "相册", "GalleryActivity.onCreate/load", "App 自绘", "档案栏、类型、统一筛选、网格、多选操作"),
    ("photo", "照片详情", "GalleryActivity.open", "App Dialog", "统一详情页面，保留耳别、原图和副本区分"),
    ("video", "录像回放", "GalleryActivity.open/PlaybackControls", "App Dialog + TextureView", "统一播放、进度、分享和删除"),
    ("markup", "照片标注", "GalleryActivity.editMarkup", "App Dialog + PhotoMarkup", "统一工具栏、四色、撤销重做、另存副本"),
    ("filters", "统一筛选", "GalleryActivity.chooseFilters", "App Dialog", "统一底部面板"),
    ("year-picker", "选择年份", "GalleryActivity.chooseFilters", "App AlertDialog", "与月份、耳别使用同一选择器样式"),
    ("month-picker", "选择月份", "GalleryActivity.chooseFilters", "App AlertDialog", "与年份、耳别使用同一选择器样式"),
    ("ear-picker", "选择耳别", "PreviewActivity.onCreate / GalleryActivity.chooseSide", "App AlertDialog", "全部/未标记的语义按上下文区分"),
    ("palette", "标注色板", "GalleryActivity.chooseMarkupColor", "App Dialog", "四个色点，当前颜色有额外选中标记"),
    ("profiles", "使用人档案", "ScopeUi.showProfiles", "App Dialog", "统一底部面板，当前使用人明确"),
    ("profile-edit", "添加或编辑档案", "PreviewActivity.editProfile", "App AlertDialog + EditText", "统一表单、键盘避让、重复和空昵称提示"),
    ("settings", "设置", "PreviewActivity.showSettings", "App Dialog", "统一导航列表与分组"),
    ("language", "语言选择", "PreviewActivity.showLanguages", "App AlertDialog", "跟随系统、简体、繁体、English，保留录制保护"),
    ("macro-confirm", "微距模式说明", "PreviewActivity.chooseMacroMode", "App AlertDialog", "说明实际行为，录制和保存时保护"),
    ("help", "使用帮助", "PreviewActivity.showHelp", "App AlertDialog 长文", "改为可滚动的统一阅读面板，审核过时内容"),
    ("privacy", "隐私说明", "PreviewActivity.showPrivacy", "App AlertDialog 长文", "统一阅读面板，事实逐项核对"),
    ("feedback", "反馈入口与说明", "PreviewActivity.openFeedback", "App 入口 + 系统浏览器", "审核当前跳转 GitHub 个人主页是否合适"),
    ("device-picker", "发现和选择耳镜", "PreviewActivity.chooseEar", "App AlertDialog", "发现中/无结果/已记住设备/重扫状态统一"),
    ("wifi-guide", "连接引导", "PreviewActivity.showWirelessReminder", "App AlertDialog", "审核自动连接和手动连接的提示是否准确"),
    ("wifi-permission", "权限前置说明", "PreviewActivity.chooseEar", "App AlertDialog", "解释请求目的，保留系统设置后备路径"),
    ("delete-confirm", "删除确认", "GalleryActivity.confirmDelete", "App AlertDialog", "保留明确文字动作、数量和不可恢复含义"),
    ("diagnostics", "诊断进行中", "PreviewActivity.startTest/updateTest", "App 状态文字", "分清普通用户状态与开发诊断"),
    ("diagnostic-result", "诊断结果", "PreviewActivity.finishTest/copyReport", "App 提示与复制", "统一结果与复制反馈"),
    ("save-failure", "保存失败提示", "PreviewActivity / GalleryActivity", "Toast / App 状态", "不丢标注草稿，可重试与取消"),
    ("system-permission", "系统授权窗口", "requestPermissions", "Android 系统", "统一前后引导，系统窗口受系统控制"),
    ("system-wifi", "系统 Wi-Fi 确认与设置", "EarWifi.request / openWifiSettings", "Android 系统", "审核应用名称和触发时机，系统外观不由主题包控制"),
    ("system-share", "系统分享面板", "GalleryActivity.share", "Android 系统", "审核文件、数量与 MIME，面板由系统控制"),
    ("system-browser", "系统浏览器", "PreviewActivity.openFeedback", "Android 系统", "统一 App 内的反馈说明，外部浏览器由系统或用户选择"),
]

# action ID, label, icon semantic ID, source kind, page, presentation, placement, behavior
ACTION_ROWS = [
    ("camera.start-preview", "开始取景", "ic-cover-open", "新增待审交互", "capture", "盖板＋文字", "实际取景窗内", "已连接后才可打开；请求视频，不重新连接网络"),
    ("camera.stop-preview", "收起取景", "ic-cover-close", "新增待审交互", "capture", "边缘把手＋图标", "实际取景窗左侧固定位置", "停止视频并保留网络与电量查询；录像和保存中禁用；灯光反应需实测"),
    ("profile.open", "切换档案", "ic-profile", "avatar", "capture", "图标＋文字", "顶部左侧", "录制、保存或预览期间保留现有切换保护"),
    ("settings.open", "设置", "ic-settings", "gear text", "capture", "图标", "顶部固定槽", "打开设置"),
    ("device.search", "选择设备", "ic-device-search", "device", "capture", "图标", "底部固定连接栏左侧", "发现设备，提供重扫与手动连接"),
    ("device.connect", "连接耳镜", "ic-connect", "connect", "capture", "图标＋独立状态文字", "底部固定连接栏右侧", "开始请求耳镜网络"),
    ("device.cancel", "取消连接", "ic-close", "cancel", "capture", "图标", "与连接同一槽", "停止正在进行的连接请求"),
    ("device.disconnect", "断开连接", "ic-disconnect", "disconnect", "capture", "图标＋独立状态文字", "与连接同一槽", "停止预览并释放网络请求"),
    ("camera.freeze", "定格画面", "ic-freeze", "pause（待重绘）", "capture", "图标＋定格状态", "待审核：工具栏/画面左下/当前右上", "锁住显示帧，实时流继续；冻结时禁止误拍、误录"),
    ("camera.resume", "恢复实时", "ic-resume", "play（待重绘）", "capture", "图标＋实时状态", "与定格同一槽", "恢复最新实时帧并清除检查缩放"),
    ("camera.expand", "旋转放大", "ic-expand", "expand", "capture", "图标", "实际矩形画面内部右下", "顺时针 90°，保持现有 180ms 按钮反馈和边界跟随"),
    ("camera.collapse", "恢复横向", "ic-collapse", "collapse", "capture", "图标", "与放大同一相对槽", "恢复横向；不改变拍照/录像编码方向"),
    ("camera.stabilize", "稳向", "ic-stabilize", "stabilize", "capture", "图标＋选中状态", "固定图像工具栏", "微距中禁用，保留占位"),
    ("camera.mirror", "镜像", "ic-mirror", "mirror", "capture", "图标＋选中状态", "固定图像工具栏", "切换镜像"),
    ("ear.mark", "标记耳别", "ic-ear", "tag", "capture", "图标＋状态标记", "固定图像工具栏", "打开未标记/左耳/右耳"),
    ("ear.left", "左耳", "ic-ear-left", "tag-left", "ear-picker", "图标＋文字", "统一选项行", "为当前拍摄或照片保存左耳标记"),
    ("ear.right", "右耳", "ic-ear-right", "tag-right", "ear-picker", "图标＋文字", "统一选项行", "为当前拍摄或照片保存右耳标记"),
    ("gallery.open", "相册", "ic-album", "album", "capture", "图标", "拍摄栏左侧", "保存和录像中保留现有保护"),
    ("photo.capture", "拍照", "ic-shutter", "View 圆环", "capture", "图形主动作", "拍摄栏中央", "沿用一次快门择优只存一张"),
    ("video.start", "录像", "ic-record", "View 红点", "capture", "图形＋录制时间", "拍摄栏右侧", "正常圆形与全画幅录像"),
    ("video.stop", "停止录像", "ic-record-stop", "View 红方块", "capture", "图形＋状态", "与录像同一槽", "结束编码，防止重复提交"),
    ("nav.back", "返回", "ic-back", "‹", "gallery", "图标", "页面顶部左侧", "遵循当前页面返回层级"),
    ("gallery.select", "选择", "", "", "gallery", "文字", "相册顶部右侧", "进入多选"),
    ("gallery.filter", "筛选", "ic-filter", "filter", "gallery", "图标＋当前条件", "类型栏右侧", "组合年、月、耳别"),
    ("filter.clear", "清除筛选", "", "", "filters", "文字", "筛选面板底部", "清空全部条件"),
    ("media.share", "分享", "ic-share", "share", "photo", "图标", "详情/多选操作栏", "走系统分享"),
    ("media.delete", "删除", "ic-delete", "delete", "photo", "图标；确认框用文字", "详情/多选操作栏", "保留数量确认和本地文件含义"),
    ("photo.markup", "标注", "ic-markup", "markup", "photo", "图标", "详情操作槽", "打开标注，保存生成副本"),
    ("markup.circle", "画圈", "ic-circle", "circle", "markup", "图标＋选中状态", "工具栏第 1 槽", "拖动圈选"),
    ("markup.arrow", "箭头", "ic-arrow", "arrow", "markup", "图标＋选中状态", "工具栏第 2 槽", "拖动绘制箭头"),
    ("markup.color", "颜色", "ic-color", "color", "markup", "色点＋选中状态", "工具栏第 3 槽", "仅四色；每笔保存自己的颜色"),
    ("markup.undo", "撤销", "ic-undo", "undo", "markup", "图标", "工具栏第 4 槽", "撤销最近一笔"),
    ("markup.redo", "重做", "ic-redo", "redo", "markup", "图标", "工具栏第 5 槽", "重做；新笔迹清空旧重做记录"),
    ("markup.cancel", "取消标注", "ic-close", "cancel", "markup", "图标", "固定底部左槽", "当前直接退出；是否增加未保存确认待审核"),
    ("markup.save", "另存副本", "ic-save-copy", "save", "markup", "图标＋完成反馈", "固定底部右槽", "另存同一档案，不覆盖原图，失败保留草稿"),
    ("playback.play", "播放", "ic-play", "文字按钮", "video", "图标", "进度栏左侧", "播放/结束后重播"),
    ("playback.pause", "暂停播放", "ic-pause", "文字按钮", "video", "图标", "与播放同一槽", "仅用于录像播放，不能复用于定格"),
    ("profile.add", "添加使用人", "ic-person-add", "文字按钮", "profiles", "图标＋文字", "档案面板操作行", "24 字符上限、空与重名校验"),
    ("profile.edit", "编辑当前档案", "ic-edit", "文字按钮", "profiles", "图标＋文字", "档案面板操作行", "保持档案 ID 和已存影像"),
    ("help.open", "使用帮助", "ic-help", "文字列表行", "settings", "图标＋文字", "设置导航行", "完整覆盖现有功能"),
    ("privacy.open", "隐私说明", "ic-privacy", "文字列表行", "settings", "图标＋文字", "设置导航行", "内容事实先审核"),
    ("feedback.open", "反馈", "ic-feedback", "文字列表行", "settings", "图标＋文字", "设置导航行", "当前跳转个人主页，目标待审核"),
    ("language.open", "应用语言", "ic-language", "文字列表行", "settings", "图标＋文字", "设置导航行", "三种语言和跟随系统"),
    ("diagnostics.open", "测试工具", "ic-diagnostics", "文字列表行", "settings", "图标＋文字", "设置高级组", "诊断不混入普通取景提示"),
]

COPY_PROPOSALS = {
    "connect_hint": ("打开耳镜，点下方连接。连接成功后，点开盖板开始取景。", "高", "连接与取景分离为新要求；盖板方案待审核。原生流程接入后再采用此候选文案。"),
    "wireless_title": ("连接耳镜", "高", "首次引导标题应描述用户目标。"),
    "wireless_prompt": ("打开耳镜后，在本页连接设备。需要时也可打开手机的 Wi-Fi 设置。", "高", "区分应用连接与系统手动后备路径。"),
    "continue_app": ("返回取景", "中", "继续指向不明确，需结合首次引导流程审核。"),
    "waiting_image": ("已连接，正在接收画面…", "高", "只在确认网络已连接的状态使用；其他状态应分开。"),
    "wifi_waiting": ("正在等待耳镜连接…", "高", "避免一律要求用户手动进 Wi-Fi。"),
    "wifi_no_devices": ("没有找到耳镜。请确认设备已开机，并靠近手机后重试。", "高", "协议热点名称留在诊断信息；普通提示给出下一步。"),
    "wifi_failed": ("没有连接成功。可以重新选择设备，或打开 Wi-Fi 设置。", "高", "明确结果和可操作的后备入口。"),
    "freeze_preview": ("定格画面", "高", "动作名应与新的定格图标一致。"),
    "resume_preview": ("恢复实时画面", "高", "这是动作名；已定格作为单独状态保留。"),
    "recording_for": ("当前档案 · %1$s", "高", "实时预览并不等于正在录制。"),
    "finish_recording_first": ("请先结束录像，或等待当前文件保存完成。", "高", "当前文案也被拍照保存保护使用。"),
    "macro_rotate_preview": ("旋转放大取景", "中", "区分预览和文件的方向。"),
    "macro_restore_preview": ("恢复横向取景", "中", "与旋转放大成对。"),
    "profile_switch": ("切换档案，当前为 %1$s", "中", "统一档案用语。"),
    "profile_add": ("添加使用人", "中", "档案和使用人的术语应统一审核。"),
    "profile_edit": ("编辑使用人", "中", "与添加使用人保持一致。"),
    "markup_hint": ("拖动画圈或箭头，完成后另存副本。", "中", "指出操作方法和保存结果。"),
    "markup_error": ("暂时无法保存标注。请重试，或取消返回。", "高", "当前同一句也用于打开照片失败，需拆成两个状态后采用。"),
    "photo_error": ("照片没有保存成功，请重试。", "中", "给出结果，技术原因进入日志。"),
    "video_error": ("录像没有保存成功。%1$s", "高", "技术错误详情应另列诊断，避免原始异常直接展示。"),
    "delete_warning": ("所选文件会从这个 App 的本地相册删除。已分享的副本需要在对应应用中处理。", "高", "审核是否补充不可恢复，以及文件范围。"),
    "feedback_action": ("打开反馈页面", "中", "当前实际跳转 GitHub 个人主页，链接目标需一并审核。"),
    "help_body": ("1. 打开耳镜，选择使用人档案，然后连接设备。\n\n2. 正常模式可标记左耳、右耳或未标记，再拍照或录像。\n\n3. 定格后可放大检查；微距模式可旋转放大取景。旋转取景与拍摄文件方向分开。\n\n4. 在相册按年份、月份和耳别筛选。打开照片可画圈或箭头，并另存标注副本。\n\n5. 连接或画面异常时，可在设置中运行诊断并复制结果。反馈前检查资料中的个人信息。", "高", "现有帮助仍提已撤下的同侧对照，遗漏筛选、标注、定格和微距录像。完整段落待用户审核。"),
    "privacy_body": ("", "高", "保留当前全文逐项审核。核对本机数据、标注副本、权限、系统分享与卸载影响后再改写。"),
}

EDGE_ROWS = [
    ("edge.preview-session", "连接与取景分开", "capture", "高", "用户要求已连接但未开始时显示电量与预计分钟。建议用圆形/矩形盖板开始取景、窗内边缘把手收起；固定设备/连接槽位不变。收起停止视频但保留网络，灯是否熄灭待硬件实测；定格仍继续接收视频。"),
    ("edge.freeze", "定格图标与位置待重新审核", "capture", "高", "不沿用暂停/播放。骨架默认将定格放在工具栏首位，可对比画面左下和当前右上。"),
    ("edge.rotation", "保留喜欢的旋转动画", "capture", "高", "保留 180ms 反馈、顺时针 90° 和实际矩形右下角跟随。减少动画选项需要有低动态版本。"),
    ("edge.native", "统一 App 自有弹窗", "settings", "高", "帮助、隐私、耳别、语言、删除、微距、档案表单、年份月份都要覆盖。"),
    ("edge.system", "系统界面的边界", "system-wifi", "高", "系统权限、Wi-Fi 确认、系统设置和分享面板由 Android 绘制。主题统一前后引导。"),
    ("edge.unsaved", "未保存标注退出", "markup", "高", "当前直接退出。是否增加放弃确认是行为变更，待用户决定。"),
    ("edge.long", "长文、键盘和字体放大", "help", "高", "阅读页能滚动；表单避开键盘；在 360dp、小屏与 1.3 倍字号验收。"),
    ("edge.landscape", "横屏与长英文", "capture", "中", "取景操作不重叠，弹窗内容可滚动，图标名称与无障碍标签一并检查。"),
    ("edge.empty", "空相册和空筛选", "gallery", "中", "分别说明没有拍摄和没有匹配，不隐藏清除筛选入口。"),
    ("edge.saving", "保存和录制保护", "save-failure", "高", "禁止重复提交，保存失败保留可编辑状态，不能因改皮肤覆盖原图。"),
    ("edge.battery", "电量和分钟数表述", "capture", "高", "百分比为设备读数，分钟是估算。现有观测表不是实际剩余续航验证。"),
    ("edge.legacy", "遗留字符串和废弃入口", "gallery", "中", "未引用的对照、旧标题、旧时间索引保留在清单并标记，不重建已撤功能。"),
    ("edge.feedback", "反馈链接目标", "feedback", "中", "当前打开 GitHub 个人主页。是否改为仓库 Issues 由用户审核。"),
    ("edge.device", "设备搜索与连接不要混同", "device-picker", "高", "设备搜索图标参考提供的耳镜，连接/断开状态要清楚，不让按钮位置跳动。"),
]

SOURCES = [
    {"title": "Android 可访问性", "url": "https://developer.android.com/guide/topics/ui/accessibility/apps",
     "use": "48dp 触摸范围、文本对比度和动作描述；这是工程验收底线。"},
    {"title": "Material 3 图标按钮", "url": "https://m3.material.io/components/icon-buttons/guidelines",
     "use": "共同动作与图标按钮参考，不要求所有风格都长成默认 Material。"},
    {"title": "Material 3 内容设计", "url": "https://m3.material.io/foundations/content-design/overview",
     "use": "提示语短、明确、可操作；本包中的候选措辞仍待用户审核。"},
    {"title": "Material Symbols", "url": "https://developers.google.com/fonts/docs/material_symbols",
     "use": "常用图标的基础参考；设备与定格语义需要单独重绘和审核。"},
    {"title": "Android 对话框", "url": "https://developer.android.com/develop/ui/views/components/dialogs",
     "use": "理解当前 App 自有 AlertDialog 的实现边界。"},
    {"title": "Android Wi-Fi 网络请求", "url": "https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap",
     "use": "系统确认界面与应用自己的选择器应分开盘点。"},
]


def build():
    locales = {locale: read_strings(locale) for locale in ("values", "values-zh-rTW", "values-en")}
    refs = {}
    hashes = {}
    hardcoded = []
    methods = {}
    for path in sorted(JAVA.glob("*.java")):
        text = path.read_text(encoding="utf-8")
        relative = path.relative_to(ROOT).as_posix()
        hashes[relative] = hashlib.sha256(text.encode()).hexdigest()
        method = ""
        for line_no, line in enumerate(text.splitlines(), 1):
            found = re.search(r"(?:private|protected|public)\s+(?:static\s+)?[\w<>\[\]]+\s+(\w+)\(", line)
            if found:
                method = found.group(1)
                methods[path.stem + "." + method] = {"path": relative, "line": line_no}
            for key in re.findall(r"R\.string\.(\w+)", line):
                refs.setdefault(key, []).append({"path": relative, "line": line_no, "method": method})
            for literal in re.findall(r'"((?:[^"\\]|\\.)*)"', line):
                if not re.search(r"[\u4e00-\u9fff]", literal):
                    continue
                diagnostic = path.stem in {"CameraStream", "JpegFrames", "BackgroundRoll", "RollFilter"} or method in {
                    "record", "messageWithCorrection", "finishTest", "updateTest", "startTest", "copyReport"
                }
                hardcoded.append({"id": "literal." + path.stem + "." + str(line_no) + "." + str(len(hardcoded)),
                    "category": "诊断/运行时字面量" if diagnostic else "硬编码文案",
                    "title": path.stem + ":" + str(line_no), "current": literal, "proposal": "",
                    "reason": "诊断信息与普通界面文案分开审核。" if diagnostic else "需要核对是否用户可见、是否应迁移到语言资源。",
                    "priority": "低" if diagnostic else "中", "legacy": False,
                    "source": [{"path": relative, "line": line_no, "method": method}],
                    "placeholders": [], "page": "diagnostics" if diagnostic else "settings"})
    manifest = (ROOT / "android-preview/AndroidManifest.xml").read_text(encoding="utf-8")
    for path in sorted((ROOT / "android-preview/res").glob("*/strings.xml")):
        hashes[path.relative_to(ROOT).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    hashes["android-preview/AndroidManifest.xml"] = hashlib.sha256(manifest.encode()).hexdigest()
    for line_no, line in enumerate(manifest.splitlines(), 1):
        for key in re.findall(r"@string/(\w+)", line):
            refs.setdefault(key, []).append({"path": "android-preview/AndroidManifest.xml", "line": line_no, "method": "manifest"})
    copy = []
    for key, value in locales["values"].items():
        proposal, priority, reason = COPY_PROPOSALS.get(key, ("", "中", "逐条审核名称、语气、当前行为和长度。"))
        source = refs.get(key, [])
        page = "capture"
        if source:
            first = source[0]
            if "Gallery" in first["path"]:
                page = "gallery"
            if first["method"] in {"showHelp", "showPrivacy", "showSettings", "showLanguages"}:
                page = {"showHelp": "help", "showPrivacy": "privacy", "showSettings": "settings", "showLanguages": "language"}[first["method"]]
        copy.append({"id": "copy." + key, "key": key, "title": key, "category": "资源文案",
            "current": value, "proposal": proposal, "priority": priority,
            "reason": reason if source else "当前 Java/Manifest 未引用，列为遗留候选；不要重新引入废弃功能。",
            "legacy": not source, "source": source, "page": page,
            "translations": {"zh-TW": locales["values-zh-rTW"].get(key, ""), "en": locales["values-en"].get(key, "")},
            "placeholders": re.findall(r"%(?:\d+\$)?[sd]|%%", value)})
    pages = [{"id": row[0], "title": row[1], "sourceMethod": row[2], "owner": row[3], "treatment": row[4]} for row in PAGE_ROWS]
    actions = [{"id": row[0], "title": row[1], "icon": row[2], "currentKind": row[3], "page": row[4],
                "presentation": row[5], "placement": row[6], "behavior": row[7]} for row in ACTION_ROWS]
    edges = [{"id": row[0], "title": row[1], "page": row[2], "priority": row[3], "detail": row[4]} for row in EDGE_ROWS]
    snapshot_input = {"sources": hashes, "catalog": hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    snapshot = hashlib.sha256(json.dumps(snapshot_input, sort_keys=True).encode()).hexdigest()[:16]
    data = {"schemaVersion": "1.0", "snapshot": snapshot, "sourceCommit": subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(), "date": "2026-11-11",
        "appVersion": ET.fromstring(manifest).get("{http://schemas.android.com/apk/res/android}versionName"),
        "pages": pages, "actions": actions, "copy": copy + hardcoded,
        "edges": edges, "sources": SOURCES, "sourceHashes": hashes, "methods": methods}
    write_json(PACKAGE / "inventory.json", data)
    (PACKAGE / "inventory.js").write_text("window.UI_REVIEW_DATA = " + json.dumps(data, ensure_ascii=False) + ";\n", encoding="utf-8")
    with (PACKAGE / "文案审核.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(["ID", "源快照", "分类", "优先级", "当前文案", "候选文案", "审核原因", "遗留", "英文", "繁体", "来源", "审核结论", "你的修改或意见"])
        for item in data["copy"]:
            source = "; ".join(ref["path"] + ":" + str(ref["line"]) for ref in item["source"])
            writer.writerow([item["id"], snapshot, item["category"], item["priority"], item["current"], item["proposal"],
                item["reason"], "是" if item["legacy"] else "", item.get("translations", {}).get("en", ""),
                item.get("translations", {}).get("zh-TW", ""), source, "待审核", ""])
    icons = sorted({action["icon"] for action in actions if action["icon"]})
    write_json(PACKAGE / "contracts/required-icons.json", {"schemaVersion": "1.0", "icons": icons})
    write_json(PACKAGE / "contracts/android-mapping.json", {"snapshot": snapshot,
        "notice": "主题包是设计输入。当前 Android 为 Java 原生 Views，不能直接运行 HTML/CSS。Codex 按此映射接入并进行真机回归。",
        "tokens": {"colors": "ScopeUi constants/backdrop/pill", "radii": "ScopeUi.shape/pill", "sizes": "ScopeUi.dp/iconButton",
                   "type": "ScopeUi.label/button", "motion": "PreviewActivity.rotateMacroButton"},
        "icons": [{"action": a["id"], "semanticId": a["icon"], "currentKind": a["currentKind"], "page": a["page"]} for a in actions if a["icon"]],
        "methods": methods})
    placeholder = '<svg xmlns="http://www.w3.org/2000/svg"><defs>\n'
    for icon in icons:
        placeholder += '<symbol id="' + icon + '" viewBox="0 0 24 24"><rect x="4" y="4" width="16" height="16" rx="3" fill="none" stroke="currentColor" stroke-width="1.6" stroke-dasharray="3 2"/><circle cx="12" cy="12" r="2" fill="currentColor"/></symbol>\n'
    placeholder += "</defs></svg>\n"
    starter = PACKAGE / "starter-theme"
    starter.mkdir(exist_ok=True)
    (starter / "icons.svg").write_text(placeholder, encoding="utf-8")
    tokens = json.loads((starter / "tokens.json").read_text(encoding="utf-8"))
    (starter / "theme.js").write_text("window.UI_STARTER_TOKENS = " + json.dumps(tokens, ensure_ascii=False) + ";\n", encoding="utf-8")
    print(json.dumps({"pages": len(pages), "actions": len(actions), "resourceCopy": len(copy),
                      "literalCopy": len(hardcoded), "requiredIcons": len(icons), "snapshot": snapshot}))


if __name__ == "__main__":
    build()
