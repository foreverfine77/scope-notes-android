# 微镜记 · Scope Notes

连接 Wi-Fi 可视耳镜的 Android 预览与影像记录工具。支持实时预览、拍照、无声录像、分人档案和同侧照片对比。

- [项目历史](HISTORY.md)区分了样机实测、导入时的实现状态和后续计划。
- [硬件简报](硬件简报.md)记录样机能力、通信方式和待验证命令。
- [电量观察记录](电量观察记录.md)保存待补充的剩余电量和分钟数读数。
- [Issues](https://github.com/foreverfine77/scope-notes-android/issues)记录任务、验收条件和测试证据。[Projects](https://github.com/users/foreverfine77/projects/3)跟踪任务进度。

## 构建与试用

在仓库根目录使用 PowerShell，准备 JDK 17、Android SDK 的 `android.jar`、Build Tools 和 ADB。将下面的路径替换为本机路径。

```powershell
.\android-preview\build.ps1 -Jdk 'C:\path\to\jdk-17' -AndroidJar 'C:\path\to\android.jar' -BuildTools 'C:\path\to\build-tools'
.\android-preview\install-preview.cmd
```

构建生成 `android-preview/earclean-preview.apk`。手机开启 USB 调试并授权后可安装。电脑接入耳镜热点后，可运行 `python earclean_probe.py` 验证是否能收到 JPEG 帧。

仓库保存源码、构建脚本、设计稿和项目文档。原厂 APK、构建产物、本地工具链、签名文件及样机抓图和原始日志留在本地。提交测试证据时使用可公开于项目成员的样本，避免上传个人耳部影像。
