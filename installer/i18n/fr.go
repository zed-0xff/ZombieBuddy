package i18n

var messagesFR = map[string]string{
	// ── Application ──
	"app.title":              "Programme d'installation ZombieBuddy v%s",
	"app.separator":          "--------------------------------------",
	"app.press_enter":        "Appuyez sur Entrée pour quitter...",
	"app.install_complete":   "Installation terminée ! Vous pouvez maintenant lancer Steam et démarrer Project Zomboid.",
	"app.uninstall_complete": "Désinstallation terminée ! Vous pouvez maintenant lancer Steam et démarrer Project Zomboid.",
	"app.nothing_changed":    "Aucune modification.",
	"app.okay_nothing":       "D'accord, rien n'a été modifié.",
	"app.errors_encountered": "%s a rencontré des erreurs. Veuillez consulter les messages ci-dessus.",

	// ── Invite : action principale ──
	"prompt.action.title":   "Que puis-je faire pour vous ?",
	"prompt.action.lines":   "  1) Installer ZombieBuddy\n  2) Désinstaller ZombieBuddy\n  3) Rien, merci !",
	"prompt.action.hint":    "Toute modification du système sera affichée pour confirmation avant d'être appliquée.",
	"prompt.action.prompt":  "Choisissez 1, 2 ou 3 : ",
	"prompt.action.eof_err": "aucune action sélectionnée",
	"prompt.action.invalid": "Veuillez choisir 1 pour installer, 2 pour désinstaller ou 3 pour ne rien faire.",

	// ── Invite : cibles d'installation ──
	"prompt.targets.title":   "Quel mode de lancement ZombieBuddy doit-il modifier ?",
	"prompt.targets.lines":   "  1) Les deux\n  2) Lancement normal\n  3) Lancement alternatif",
	"prompt.targets.prompt":  "Choisissez 1, 2 ou 3 : ",
	"prompt.targets.eof_err": "aucun mode de lancement sélectionné",
	"prompt.targets.invalid": "Veuillez choisir 1, 2 ou 3.",

	// ── Invite : installation normale ──
	"prompt.normal.title":   "Comment le lancement normal doit-il être modifié ?",
	"prompt.normal.lines":   "  1) Les deux\n  2) ProjectZomboid64.json\n  3) Options de lancement Steam",
	"prompt.normal.prompt":  "Choisissez 1, 2 ou 3 : ",
	"prompt.normal.eof_err": "aucune modification de lancement normal sélectionnée",
	"prompt.normal.invalid": "Veuillez choisir 1, 2 ou 3.",

	// ── Confirmation ──
	"confirm.no_changes":        "Aucune modification système n'est nécessaire.",
	"confirm.will_make_changes": "ZombieBuddy va effectuer les modifications suivantes :",
	"confirm.prompt":            "Continuer ? [y/N] : ",

	// ── Noms d'actions ──
	"action.install":   "Installation",
	"action.uninstall": "Désinstallation",
	"action.nothing":   "Rien",
	"action.operation": "Opération",

	// ── Messages d'installation ──
	"install.err_select_targets": "Erreur lors de la sélection des cibles de lancement : %v",
	"install.err_read_confirm":   "Erreur lors de la lecture de la confirmation : %v",
	"install.cancelled":          "Installation annulée.",
	"install.err_copy_core":      "Erreur lors de la copie des fichiers principaux : %v",
	"install.success_copy":       "zbNative.dll et ZombieBuddy.jar installés avec succès",
	"install.err_json":           "Erreur lors de la mise à jour de ProjectZomboid64.json : %v",
	"install.json_updated":       "ProjectZomboid64.json mis à jour pour le mode de lancement normal",
	"install.json_already":       "ProjectZomboid64.json contient déjà l'agent ZombieBuddy.",
	"install.err_batch":          "Erreur lors de la mise à jour de ProjectZomboid64.bat : %v",
	"install.batch_updated":      "ProjectZomboid64.bat mis à jour pour le mode de lancement alternatif",
	"install.batch_already":      "ProjectZomboid64.bat contient déjà l'agent ZombieBuddy.",
	"install.err_steam":          "Erreur lors de la mise à jour des options de lancement Steam : %v",

	// ── Messages de désinstallation ──
	"uninstall.err_plan":      "Erreur lors de la planification de la désinstallation : %v",
	"uninstall.nothing":       "Rien à désinstaller.",
	"uninstall.cancelled":     "Désinstallation annulée.",
	"uninstall.err_apply":     "Erreur lors de l'application des modifications de désinstallation : %v",
	"uninstall.err_json":      "mise à jour de ProjectZomboid64.json : %v",
	"uninstall.json_removed":  `"%s" supprimé de ProjectZomboid64.json`,
	"uninstall.err_batch":     "mise à jour de ProjectZomboid64.bat : %v",
	"uninstall.batch_removed": `"%s" supprimé de ProjectZomboid64.bat`,
	"uninstall.err_rm_core":   "suppression des fichiers principaux : %v",
	"uninstall.err_rm_steam":  "suppression des options de lancement Steam : %v",

	// ── Aperçu / planification ──
	"preview.copy":         `copier "%s\%s"    vers "%s\\"`,
	"preview.add_json":     `ajouter "%s" à "%s"`,
	"preview.add_steam":    `ajouter "%s" aux options de lancement Steam de PZ`,
	"preview.remove":       `supprimer "%s" de "%s"`,
	"preview.remove_steam": `supprimer "%s" des options de lancement Steam de PZ`,
	"preview.delete":       `supprimer "%s"`,

	// ── Fermeture de Steam ──
	"steam.registry_error":        "impossible de trouver la clé de registre Steam",
	"steam.registry_windows_only": "la recherche dans le registre Steam n'est prise en charge que sous Windows",
	"steam.running":               "Steam est actuellement en cours d'exécution.",
	"steam.close_instructions1":   "    Veuillez fermer complètement Steam avant de continuer.",
	"steam.close_instructions2":   "    (Clic droit sur Steam dans la barre des tâches -> Quitter)",
	"steam.press_enter_after":     "Appuyez sur Entrée après avoir fermé Steam...",
	"steam.now_closed":            "Steam est maintenant fermé. Poursuite de %s...",
	"steam.launch_opt_update":     "mise à jour des options de lancement",

	// ── Erreurs de chemin ──
	"path.pz_not_found": "impossible de trouver l'installation de Project Zomboid",
	"path.zb_not_found": "Assurez-vous d'être abonné à https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853",

	// ── Erreurs VDF / bibliothèque ──
	"vdf.invalid_library":   "libraryfolders.vdf invalide",
	"vdf.app_not_found":     "application introuvable dans aucune bibliothèque Steam",
	"vdf.no_config":         "impossible de créer la configuration de lancement de Project Zomboid",
	"vdf.already_has_agent": "Les options de lancement contiennent déjà l'agent ZombieBuddy.",
	"vdf.no_appstate":       "impossible de trouver AppState pour %s",

	// ── Autres erreurs ──
	"err.check_json":            "vérification de ProjectZomboid64.json : %v",
	"err.check_batch":           "vérification de ProjectZomboid64.bat : %v",
	"err.windows_only":          "Erreur : ce programme d'installation est conçu uniquement pour Windows.",
	"err.source_not_found":      "source %s introuvable",
	"err.copy_failed":           "échec de la copie de %s : %v",
	"err.remove_failed":         "échec de la suppression de %s : %v",
	"err.nav_not_map":           "la clé %s n'est pas un map",
	"err.nav_key_not_found":     "clé %s introuvable",
	"err.unknown_launch_mode":   "mode de lancement inconnu",
	"err.unknown_normal_target": "cible de lancement normal inconnue",

	// ── Informations de copie / suppression ──
	"copy.copying":               `copie de "%s" vers "%s"`,
	"remove.removed":             `%s supprimé`,
	"remove.steam_failed_remove": "échec de la suppression des options de lancement de %s : %v",
	"remove.steam_removed":       "Options de lancement supprimées de %s",

	// ── Messages utilisateur Steam ──
	"steam.updated_user":  "Options de lancement mises à jour pour l'utilisateur %s",
	"steam.failed_user":   "Échec de la mise à jour des options de lancement pour l'utilisateur %s : %v",
	"steam.no_users":      "aucune configuration utilisateur trouvée à mettre à jour",
	"steam.skipping_user": "options de lancement Steam ignorées pour l'utilisateur %s : %v",
}
