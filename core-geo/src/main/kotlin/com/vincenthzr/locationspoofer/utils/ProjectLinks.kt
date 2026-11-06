package com.vincenthzr.locationspoofer.utils

/**
 * 项目自身的对外地址。
 *
 * 只在这里写一次仓库 slug，其余地方一律引用本对象——避免同一串地址散落在
 * 更新检查、适配进度拉取、关于页跳转三处，改仓库时漏改一处就会出现
 * 「关于页指向 A、更新检查指向 B」这种自相矛盾。
 */
object ProjectLinks {

    const val REPO_SLUG: String = "meat-daemon-404/LocationSpoofer"

    /** 仓库主页 */
    const val REPO_URL: String = "https://github.com/" + REPO_SLUG

    /** Issues 列表（提 issue 前先搜） */
    const val ISSUES_URL: String = REPO_URL + "/issues"

    /** 最新 Release 列表 API（更新检查用） */
    const val RELEASES_API_URL: String = "https://api.github.com/repos/" + REPO_SLUG + "/releases"

    /** raw 直链（国内常被墙，调用方应准备 jsDelivr 镜像兜底） */
    fun rawUrl(branch: String, path: String): String =
        "https://raw.githubusercontent.com/" + REPO_SLUG + "/" + branch + "/" + path

    /** jsDelivr 镜像，用于 raw 拉不通时兜底 */
    fun jsDelivrUrl(branch: String, path: String): String =
        "https://cdn.jsdelivr.net/gh/" + REPO_SLUG + "@" + branch + "/" + path
}
