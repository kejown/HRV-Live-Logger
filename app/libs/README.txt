# ANT+ SDK 目录
#
# 本工程已内置官方 AAR（antpluginlib_3-9-0.aar），开箱即可编译。
#
# 如需更新版本，下载地址（thisisant.com 已整体迁移至 Garmin 平台）：
#   https://developer.garmin.com/downloads/ant/Android_API.zip
#
# 解包后取 AntPlus_Android_SDK_v.P.B7.zip 里的：
#   ANT+_Android_SDK/API/antpluginlib_x-y-z.aar
#
# app/build.gradle.kts 用 fileTree 引入本目录下所有 *.aar / *.jar，
# 放进来直接重新构建即可，无需改代码。
#
# 换成新版本后务必跑 :app:testDebugUnitTest，
# SdkReflectionContractTest 会校验反射用的签名是否还对得上。
#
# 没有这个 AAR 时工程仍可编译运行，只是 ANT+ 功能会提示不可用。
