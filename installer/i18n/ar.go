package i18n

var messagesAR = map[string]string{
	// ── التطبيق ──
	"app.title":              "مثبت ZombieBuddy لنظام Windows v%s",
	"app.separator":          "---------------------------------------",
	"app.press_enter":        "اضغط Enter للخروج...",
	"app.install_complete":   "اكتمل التثبيت! يمكنك الآن تشغيل Steam وتشغيل Project Zomboid.",
	"app.uninstall_complete": "اكتملت الإزالة! يمكنك الآن تشغيل Steam وتشغيل Project Zomboid.",
	"app.nothing_changed":    "لم يتم إجراء أي تغييرات.",
	"app.okay_nothing":       "حسناً، لم يتم تغيير أي شيء.",
	"app.errors_encountered": "واجه %s أخطاء. يرجى مراجعة الرسائل أعلاه.",

	// ── السؤال: الإجراء الرئيسي ──
	"prompt.action.title":   "ماذا يمكنني أن أفعل لك؟",
	"prompt.action.line1":   "  1) تثبيت ZombieBuddy",
	"prompt.action.line2":   "  2) إزالة ZombieBuddy",
	"prompt.action.line3":   "  3) لا شيء، شكراً!",
	"prompt.action.hint":    "سيتم عرض أي تغييرات في النظام للتأكيد قبل تطبيقها.",
	"prompt.action.prompt":  "اختر 1 أو 2 أو 3: ",
	"prompt.action.eof_err": "لم يتم تحديد أي إجراء",
	"prompt.action.invalid": "يرجى اختيار 1 للتثبيت، أو 2 للإزالة، أو 3 لعدم القيام بأي شيء.",

	// ── السؤال: أهداف التثبيت ──
	"prompt.targets.title":   "ما وضع التشغيل الذي يجب على ZombieBuddy تعديله؟",
	"prompt.targets.line1":   "  1) كلاهما",
	"prompt.targets.line2":   "  2) التشغيل العادي",
	"prompt.targets.line3":   "  3) التشغيل البديل",
	"prompt.targets.prompt":  "اختر 1 أو 2 أو 3: ",
	"prompt.targets.eof_err": "لم يتم تحديد وضع التشغيل",
	"prompt.targets.invalid": "يرجى اختيار 1 أو 2 أو 3.",

	// ── السؤال: التثبيت العادي ──
	"prompt.normal.title":   "كيف يجب تعديل التشغيل العادي؟",
	"prompt.normal.line1":   "  1) كلاهما",
	"prompt.normal.line2":   "  2) ProjectZomboid64.json",
	"prompt.normal.line3":   "  3) خيارات تشغيل Steam",
	"prompt.normal.prompt":  "اختر 1 أو 2 أو 3: ",
	"prompt.normal.eof_err": "لم يتم تحديد تعديل للتشغيل العادي",
	"prompt.normal.invalid": "يرجى اختيار 1 أو 2 أو 3.",

	// ── التأكيد ──
	"confirm.no_changes":        "لا حاجة لتغييرات في النظام.",
	"confirm.will_make_changes": "سيقوم ZombieBuddy بإجراء هذه التغييرات:",
	"confirm.prompt":            "المتابعة؟ [y/N]: ",

	// ── أسماء الإجراءات ──
	"action.install":   "التثبيت",
	"action.uninstall": "الإزالة",
	"action.nothing":   "لا شيء",
	"action.operation": "العملية",

	// ── رسائل التثبيت ──
	"install.err_select_targets": "خطأ في تحديد أهداف التشغيل: %v",
	"install.err_read_confirm":   "خطأ في قراءة التأكيد: %v",
	"install.cancelled":          "تم إلغاء التثبيت.",
	"install.err_copy_core":      "خطأ في نسخ الملفات الأساسية: %v",
	"install.success_copy":       "تم تثبيت zbNative.dll و ZombieBuddy.jar بنجاح",
	"install.err_json":           "خطأ في تحديث ProjectZomboid64.json: %v",
	"install.json_updated":       "تم تحديث ProjectZomboid64.json لوضع التشغيل العادي",
	"install.json_already":       "ProjectZomboid64.json يحتوي بالفعل على وكيل ZombieBuddy.",
	"install.err_batch":          "خطأ في تحديث ProjectZomboid64.bat: %v",
	"install.batch_updated":      "تم تحديث ProjectZomboid64.bat لوضع التشغيل البديل",
	"install.batch_already":      "ProjectZomboid64.bat يحتوي بالفعل على وكيل ZombieBuddy.",
	"install.err_steam":          "خطأ في تحديث خيارات تشغيل Steam: %v",

	// ── رسائل الإزالة ──
	"uninstall.err_plan":      "خطأ في تخطيط الإزالة: %v",
	"uninstall.nothing":       "لا شيء لإزالته.",
	"uninstall.cancelled":     "تم إلغاء الإزالة.",
	"uninstall.err_apply":     "خطأ في تطبيق تغييرات الإزالة: %v",
	"uninstall.err_json":      "تحديث ProjectZomboid64.json: %v",
	"uninstall.json_removed":  `تمت إزالة "%s" من ProjectZomboid64.json`,
	"uninstall.err_batch":     "تحديث ProjectZomboid64.bat: %v",
	"uninstall.batch_removed": `تمت إزالة "%s" من ProjectZomboid64.bat`,
	"uninstall.err_rm_core":   "إزالة الملفات الأساسية: %v",
	"uninstall.err_rm_steam":  "إزالة خيارات تشغيل Steam: %v",

	// ── اكتشاف المسارات ──
	"detect.err_steam":   "خطأ في اكتشاف Steam: %v",
	"detect.path_format": "[.] %-*s موجود في %s",
	"detect.err_pz":      "خطأ في اكتشاف Project Zomboid: %v",
	"detect.err_zb":      "خطأ في اكتشاف تعديل ZombieBuddy: %v",
	"detect.steam_label": "Steam",
	"detect.pz_label":    "PZ",
	"detect.zb_label":    "ZB",

	// ── المعاينة / الخطة ──
	"preview.copy":         `نسخ "%s\%s"    إلى "%s\\"`,
	"preview.add_json":     `إضافة "%s" إلى "%s"`,
	"preview.add_steam":    `إضافة "%s" إلى خيارات تشغيل Steam لـ PZ`,
	"preview.remove":       `إزالة "%s" من "%s"`,
	"preview.remove_steam": `إزالة "%s" من خيارات تشغيل Steam لـ PZ`,
	"preview.delete":       `حذف "%s"`,

	// ── إغلاق Steam ──
	"steam.registry_error":        "تعذر العثور على مفتاح تسجيل Steam",
	"steam.registry_windows_only": "البحث في سجل Steam مدعوم فقط على Windows",
	"steam.running":               "Steam قيد التشغيل حالياً.",
	"steam.close_instructions1":   "    يرجى إغلاق Steam بالكامل قبل المتابعة.",
	"steam.close_instructions2":   "    (انقر بزر الماوس الأيمن على Steam في شريط النظام -> خروج)",
	"steam.press_enter_after":     "اضغط Enter بعد إغلاق Steam...",
	"steam.now_closed":            "تم إغلاق Steam. متابعة %s...",
	"steam.launch_opt_update":     "تحديث خيارات التشغيل",

	// ── أخطاء المسار ──
	"path.pz_not_found": "تعذر العثور على تثبيت Project Zomboid",
	"path.zb_not_found": "تأكد من اشتراكك في https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853",

	// ── أخطاء VDF / المكتبة ──
	"vdf.invalid_library":   "libraryfolders.vdf غير صالح",
	"vdf.app_not_found":     "لم يتم العثور على التطبيق في أي مكتبة Steam",
	"vdf.no_config":         "تعذر إنشاء تكوين تشغيل Project Zomboid",
	"vdf.already_has_agent": "خيارات التشغيل تحتوي بالفعل على وكيل ZombieBuddy.",
	"vdf.no_appstate":       "تعذر العثور على AppState لـ %s",

	// ── أخطاء أخرى ──
	"err.check_json":            "التحقق من ProjectZomboid64.json: %v",
	"err.check_batch":           "التحقق من ProjectZomboid64.bat: %v",
	"err.windows_only":          "خطأ: هذا المثبت مصمم لنظام Windows فقط.",
	"err.source_not_found":      "تعذر العثور على المصدر %s",
	"err.copy_failed":           "فشل نسخ %s: %v",
	"err.remove_failed":         "فشل إزالة %s: %v",
	"err.nav_not_map":           "المفتاح %s ليس خريطة",
	"err.nav_key_not_found":     "المفتاح %s غير موجود",
	"err.unknown_launch_mode":   "وضع تشغيل غير معروف",
	"err.unknown_normal_target": "هدف تشغيل عادي غير معروف",

	// ── معلومات النسخ / الإزالة ──
	"copy.copying":               `جاري نسخ "%s" إلى "%s"`,
	"remove.removed":             `تمت إزالة %s`,
	"remove.steam_failed_remove": "فشل إزالة خيارات التشغيل من %s: %v",
	"remove.steam_removed":       "تمت إزالة خيارات التشغيل من %s",

	// ── رسائل مستخدم Steam ──
	"steam.updated_user":  "تم تحديث خيارات التشغيل للمستخدم %s",
	"steam.failed_user":   "فشل تحديث خيارات التشغيل للمستخدم %s: %v",
	"steam.no_users":      "لم يتم العثور على تكوينات مستخدمين للتحديث",
	"steam.skipping_user": "تخطي خيارات تشغيل Steam للمستخدم %s: %v",
}
