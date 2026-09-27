export type TroubleshootingItem = {
  title: string;
  cause: string;
  steps: string[];
  verify?: string;
};

export type TroubleshootingGroup = {
  id: string;
  label: string;
  intro: string;
  items: TroubleshootingItem[];
};

export type LogStep = {
  title: string;
  body: string;
};

export const logSteps: LogStep[] = [
  {
    title: "先让问题出现一次",
    body: "例如启动服务失败、点 GO 不翻译、没有语音、测试 API 失败，或模拟器没有截图。"
  },
  {
    title: "打开错误纪录",
    body: "回到 FgoGotran，进入设置页，点击错误纪录。"
  },
  {
    title: "看最上面的记录",
    body: "最新问题会在上方。重点看标题、code、detail、server、mode、speaker。"
  },
  {
    title: "导出 TXT",
    body: "点击导出 TXT，然后把这个文件发给作者。不要只发一张截图。"
  },
  {
    title: "一起说明使用环境",
    body: "告诉作者你用的是手机还是模拟器、FGO 服务器、翻译模式，以及 FgoGotran 版本。"
  }
];

export const troubleshootingGroups: TroubleshootingGroup[] = [
  {
    id: "phone",
    label: "手机用户",
    intro: "真机问题通常比较少，优先检查权限、API、语音和 OCR 时机。",
    items: [
      {
        title: "启动服务前要检查什么？",
        cause: "大多数真机问题来自悬浮窗、无障碍、API Key 或语音 Key。",
        steps: [
          "确认悬浮窗权限已开启。",
          "确认 FgoGotran 无障碍服务已开启。",
          "按钮不出现时，先在系统里关闭再重新开启无障碍服务，这通常是最关键的修复。",
          "首页如果显示未启用，先处理对应权限再启动服务。"
        ]
      },
      {
        title: "点了 GO 但不翻译",
        cause: "日服才需要翻译 API；简中服、繁中服主要是读取中文文本并朗读。",
        steps: [
          "先到 API 设置页点“测试 API”。",
          "确认 API Key、模型名和接口地址正确。",
          "简中服、繁中服改检查语音设置，而不是翻译 API。"
        ],
        verify: "“测试 API”成功，回到游戏点 GO 能出现译文。"
      },
      {
        title: "没有语音",
        cause: "AI 语音未开启、Speech Key 未填写或区域不正确。",
        steps: [
          "进入语音设置，确认 AI 语音开启。",
          "确认 Speech Key 已填写、区域正确。",
          "点“测试语音”确认能返回音频。"
        ],
        verify: "测试语音成功，游戏内朗读正常。"
      },
      {
        title: "OCR 不准或漏翻",
        cause: "文字未完全显示，或裁剪范围包含了太多无关 UI。",
        steps: [
          "等文字完全显示后再点 GO。",
          "裁剪模式请框住完整文字区域。",
          "避免把太多无关 UI 放进去。"
        ],
        verify: "重新点 GO 后译文覆盖在正确文字附近。"
      }
    ]
  },
  {
    id: "emulator",
    label: "模拟器用户",
    intro: "模拟器更容易出问题，重点看 64 位实例、Android 版本、截图、图形渲染、悬浮窗和包名。",
    items: [
      {
        title: "用哪个模拟器或实例？",
        cause: "旧版、32 位或测试版内核更容易遇到截图、OCR、原生库和无障碍兼容问题。",
        steps: [
          "优先新建 64 位实例。",
          "确认 Android 版本是 11 或以上。",
          "MuMu、雷电、BlueStacks 都优先使用 64 位实例。"
        ]
      },
      {
        title: "点了 GO 但不翻译",
        cause: "要先区分是 API/OCR 问题，还是模拟器画面事件问题。",
        steps: [
          "先用手动 GO 测一次。",
          "手动 GO 能用，再测试半自动和全自动。",
          "手动 GO 也不能用，就先看截图、OCR、API 或语音设置。"
        ],
        verify: "手动 GO 能翻译，说明 API 和 OCR 基本可用。"
      },
      {
        title: "没有返回截图 / 当前显示器无效 / bitmap_null",
        cause: "多半是模拟器图形层问题，Android 没有给应用可用的游戏截图。",
        steps: [
          "优先切换 OpenGL、DirectX、Vulkan 渲染器。",
          "重启模拟器。",
          "重新重现一次问题，再查看错误纪录。"
        ],
        verify: "切换渲染器并重启后，手动 GO 能返回截图。"
      },
      {
        title: "按钮不显示或不能点",
        cause: "模拟器可能显示权限已允许，但实际拦截悬浮窗或无障碍。",
        steps: [
          "重新开启悬浮窗权限。",
          "在系统无障碍设置里关闭 FgoGotran，再重新开启——这通常是最关键的一步。",
          "启动服务，确认 GO / 半 / 全 是否出现。"
        ],
        verify: "启动服务后 GO / 半 / 全 按钮正常显示。"
      },
      {
        title: "半自动 / 全自动不动",
        cause: "手动 GO 正常但自动模式不动，通常不是 API 问题，而是模拟器画面事件、截图刷新或游戏窗口状态不稳定。",
        steps: [
          "确认手动 GO 正常。",
          "检查游戏窗口是否稳定、未被遮挡。",
          "观察画面变化检测是否触发。"
        ]
      },
      {
        title: "渠道服或特殊包名",
        cause: "B 服、台服、渠道服或模拟器改包名可能不在支持列表里。",
        steps: [
          "导出错误纪录 TXT。",
          "确认 TXT 里包含 package、class、app label。"
        ],
        verify: "把 TXT 发给作者，方便加入支持。"
      }
    ]
  },
  {
    id: "projection",
    label: "投屏到电脑",
    intro: "电脑能看到游戏画面，不代表 FgoGotran 一定能读取画面；关键是手机本机画面是否仍然真实显示。",
    items: [
      {
        title: "为什么电脑有画面但 App 不能翻译？",
        cause: "投屏软件只是把手机画面显示到电脑。FgoGotran 仍然运行在手机里，只能通过 Android 截图读取手机当前显示内容。",
        steps: [
          "确认手机屏幕仍然真实亮起显示游戏。",
          "不要使用会让手机屏幕黑屏的投屏模式。"
        ]
      },
      {
        title: "为什么手机黑屏会失败？",
        cause: "手机屏幕关闭、变黑或进入省电投屏模式时，Android 可能不给应用真实游戏截图，所以 OCR 没文字。",
        steps: [
          "保持手机屏幕亮起。",
          "关闭息屏、黑屏或省电投屏模式。"
        ]
      },
      {
        title: "推荐用什么方式投屏？",
        cause: "有线投屏比无线更稳定，也能保持屏幕常亮。",
        steps: [
          "优先 USB 有线投屏。",
          "保持手机屏幕亮起。",
          "scrcpy 可以使用 stay-awake / keep-active 选项。"
        ]
      },
      {
        title: "亮度影响大吗？",
        cause: "普通屏幕亮度通常不影响 Android 截图像素。",
        steps: [
          "真正影响的是息屏、黑屏、隐私屏或特殊显示层，优先排查这些。"
        ]
      }
    ]
  },
  {
    id: "error-meaning",
    label: "常见错误含义",
    intro: "错误纪录里看到这些标题时，可以先按对应方法处理。",
    items: [
      {
        title: "悬浮窗权限未授权",
        cause: "App 没有显示在其他应用上层的权限，所以按钮无法显示在 FGO 上。",
        steps: [
          "去系统设置允许 FgoGotran 显示在其他应用上层。",
          "回到首页启动服务。"
        ]
      },
      {
        title: "无障碍服务未启用",
        cause: "Android 没有把游戏画面事件交给 FgoGotran。",
        steps: [
          "进入系统无障碍设置。",
          "即使显示已开启，也先关闭 FgoGotran 再重新开启一次。"
        ]
      },
      {
        title: "没有返回截图 / 当前显示器无效 / bitmap_null",
        cause: "Android 或模拟器没有给 App 可用的游戏截图。",
        steps: [
          "手机保持亮屏。",
          "模拟器切换图形渲染模式。",
          "投屏时不要使用黑屏模式。"
        ]
      },
      {
        title: "API 请求失败",
        cause: "翻译接口没有正常返回。",
        steps: [
          "在 API 设置页重新测试。",
          "确认 Key、模型名、接口地址、余额和网络。"
        ]
      },
      {
        title: "Azure TTS 请求失败",
        cause: "语音服务没有正常返回音频。",
        steps: [
          "在语音设置页测试语音。",
          "确认 Speech Key 与区域匹配。"
        ]
      },
      {
        title: "未支持的 FGO 包名",
        cause: "App 看到一个疑似 FGO 的包名，但还没有加入支持列表。",
        steps: [
          "导出错误纪录 TXT，里面会包含 package、class 和 app label。"
        ]
      }
    ]
  }
];


export const troubleshootingToc = [
  { href: "#error-log", label: "先导出错误纪录" },
  { href: "#phone", label: "手机用户" },
  { href: "#emulator", label: "模拟器用户" },
  { href: "#projection", label: "投屏到电脑" },
  { href: "#error-meaning", label: "错误含义" }
];