# File decisions

Pinned upstream `a72f9e6c3f2e25eb74ce0e7d6cc56dc33c130288` → `e36ff7aa7997be86d47aac9412c174f06128fd0d`; fork base `0864e972c9580dff9a2eb7a6a87aed5d43d98fa1`.

298 upstream-changed paths were compared. Integration/prerequisite paths are listed too. Documentation/scripts are excluded from the source table; see Git diff for those.

## direct replace (41)

| Path | Decision |
| --- | --- |
| `app/src/main/java/com/lagradost/cloudstream3/actions/VideoClickAction.kt` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/account/AccountAdapter.kt` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/account/AccountSelectActivity.kt` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/account/AccountSelectLinearItemDecoration.kt` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/player/source_priority/QualityProfileDialog.kt` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/result/ResultFragmentTv.kt` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/Globals.kt` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsAccount.kt` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsProviders.kt` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/setup/SetupFragmentLanguage.kt` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/setup/SetupFragmentMedia.kt` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/utils/BackupUtils.kt` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/utils/DataStoreHelper.kt` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/utils/PowerManagerAPI.kt` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/res/drawable/outline_drawable_forced_round.xml` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/res/drawable/quick_novel_icon.xml` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/res/drawable/rounded_outline.xml` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/res/layout/account_edit_dialog.xml` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/res/layout/account_list_item.xml` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/res/layout/account_list_item_add.xml` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/res/layout/account_list_item_edit.xml` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/res/layout/account_select_linear.xml` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/res/layout/activity_account_select.xml` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/res/layout/activity_main.xml` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/res/layout/activity_main_tv.xml` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/res/layout/bottom_selection_dialog.xml` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/res/layout/fragment_result.xml` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/res/layout/fragment_result_tv.xml` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/res/layout/fragment_setup_extensions.xml` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/res/layout/fragment_setup_language.xml` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/res/layout/fragment_setup_layout.xml` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/res/layout/fragment_setup_media.xml` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/res/layout/fragment_setup_provider_languages.xml` | Original fork equals baseline; current blob equals pinned upstream. |
| `app/src/main/res/values/attrs.xml` | Original fork equals baseline; current blob equals pinned upstream. |
| `build.gradle.kts` | Original fork equals baseline; current blob equals pinned upstream. |
| `gradle/libs.versions.toml` | Original fork equals baseline; current blob equals pinned upstream. |
| `library/src/commonMain/kotlin/com/lagradost/cloudstream3/ParCollections.kt` | Original fork equals baseline; current blob equals pinned upstream. |
| `library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/Firestream.kt` | Original fork equals baseline; current blob equals pinned upstream. |
| `library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/Vidsonic.kt` | Original fork equals baseline; current blob equals pinned upstream. |
| `library/src/commonMain/kotlin/com/lagradost/cloudstream3/utils/M3u8Helper.kt` | Original fork equals baseline; current blob equals pinned upstream. |
| `library/src/commonMain/kotlin/com/lagradost/cloudstream3/utils/SubtitleHelper.kt` | Original fork equals baseline; current blob equals pinned upstream. |

## added (221)

| Path | Decision |
| --- | --- |
| `.github/workflows/validate_migration.yml` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `.gitleaks.toml` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/androidTest/java/com/lagradost/cloudstream3/SettingsMigrationSmokeTest.kt` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/MainActivityScreen.kt` | New upstream file, imported unchanged. |
| `app/src/main/java/com/lagradost/cloudstream3/plugins/InternalRepositoryPolicy.kt` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/receivers/PackageInstallerStatusReceiver.kt` | New upstream file, imported unchanged. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/AdiXtreamSettingsMigration.kt` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/AdiXtreamUpdatePolicy.kt` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/ApkUpdater.kt` | New upstream file adapted for AdiXtream. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/GithubReleases.kt` | New upstream file adapted for AdiXtream. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/GithubViewModel.kt` | New upstream file adapted for AdiXtream. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsAccount2.kt` | New upstream file, imported unchanged. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsAccountScreen.kt` | New upstream file, imported unchanged. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsFragment2.kt` | New upstream file, imported unchanged. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsFragmentScreen.kt` | New upstream file adapted for AdiXtream. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsGeneral2.kt` | New upstream file, imported unchanged. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsGeneralScreen.kt` | New upstream file adapted for AdiXtream. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsPlayer2.kt` | New upstream file, imported unchanged. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsPlayerScreen.kt` | New upstream file, imported unchanged. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsProviders2.kt` | New upstream file, imported unchanged. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsProvidersScreen.kt` | New upstream file, imported unchanged. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsSubscription.kt` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsSubscriptionScreen.kt` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsUI2.kt` | New upstream file, imported unchanged. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsUIScreen.kt` | New upstream file, imported unchanged. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsUpdates2.kt` | New upstream file, imported unchanged. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsUpdatesScreen.kt` | New upstream file adapted for AdiXtream. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/SubscriptionViewModel.kt` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/logcat/LogcatDialog.kt` | New upstream file, imported unchanged. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/logcat/LogcatParser.kt` | New upstream file, imported unchanged. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/subtitles/ChromecastSubtitlesFragment2.kt` | New upstream file, imported unchanged. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/subtitles/ChromecastSubtitlesScreen.kt` | New upstream file, imported unchanged. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/subtitles/SubtitlesFragment2.kt` | New upstream file, imported unchanged. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/subtitles/SubtitlesScreen.kt` | New upstream file, imported unchanged. |
| `app/src/main/java/com/lagradost/cloudstream3/utils/BaseComposeFragment.kt` | New upstream file, imported unchanged. |
| `app/src/main/res/color/account_edit_btn_tint.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/animeskip.xml` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/main/res/drawable/article_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/aspect_ratio_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/audio_capture_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/autorenew_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/battery_alert_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/border_color_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/build_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/cast.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/close_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/closed_caption_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/colors_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/construction_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/copy_all_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/delete_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/description_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/dns_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/encrypted_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/extension_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/extention_download.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/extention_renew.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/extention_renew2.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/face_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/filter_alt_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/folder_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/font_download_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/format_align_center_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/format_bold_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/format_color_fill_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/format_color_text_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/format_italic_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/format_paint_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/hard_drive_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/high_res_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/history_toggle_off_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/ic_baseline_clock_24.xml` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/main/res/drawable/imagesearch_roller_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/keyboard_arrow_left_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/keyboard_double_arrow_right_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/label_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/language_download.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/language_download2.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/language_korean_latin_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/light_mode_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/lock_icon_bg.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/match_word_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/memory_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/memory_alt_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/mobile_arrow_down_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/mobile_code_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/mobile_wrench_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/movie_edit_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/movie_info_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/notifications_active_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/oval_stroke_focus.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/palette_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/pause_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/picture_in_picture_center_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/pip_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/play_arrow_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/plugin_lang.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/reset_colors_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/responsive_layout_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/restore_page_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/rounded_corner_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/save_as_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/save_clock_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/screen_rotation_alt_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/shadow_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/shadow_add_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/shuffle_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/skip_next_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/subtitles_gear_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/swipe_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/swipe_vertical_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/text_ad_off_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/text_fields_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/text_select_move_up_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/tooltip_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/touch_double_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/undo_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/uppercase_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/visibility_off_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/drawable/wifi_proxy_24px.xml` | New upstream file, imported unchanged. |
| `app/src/main/res/values-v31/styles.xml` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/main/res/values/adixtream-settings.xml` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/test/java/com/lagradost/cloudstream3/plugins/InternalRepositoryPolicyTest.kt` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/test/java/com/lagradost/cloudstream3/ui/settings/AdiXtreamUpdatePolicyTest.kt` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/test/java/com/lagradost/cloudstream3/ui/settings/SubscriptionViewModelTest.kt` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `desktopApp/build.gradle.kts` | New upstream file adapted for AdiXtream. |
| `desktopApp/src/desktop-icons/icon-linux.png` | New upstream file adapted for AdiXtream. |
| `desktopApp/src/desktop-icons/icon-windows.ico` | New upstream file adapted for AdiXtream. |
| `desktopApp/src/jvmMain/kotlin/com/lagradost/cloudstream4/Main.kt` | New upstream file, imported unchanged. |
| `desktopApp/src/readme.md` | New upstream file, imported unchanged. |
| `library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/Hexload.kt` | New upstream file, imported unchanged. |
| `library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/HubuCloud.kt` | New upstream file, imported unchanged. |
| `library/src/jvmTest/kotlin/com/lagradost/cloudstream3/utils/M3u8HelperTest.kt` | New upstream file, imported unchanged. |
| `shared/build.gradle.kts` | New upstream file, imported unchanged. |
| `shared/readme.md` | New upstream file, imported unchanged. |
| `shared/src/androidMain/kotlin/com/lagradost/cloudstream4/DevicePreferenceStore.android.kt` | New upstream file, imported unchanged. |
| `shared/src/androidMain/kotlin/com/lagradost/cloudstream4/compose/ComposeFragment.kt` | New upstream file, imported unchanged. |
| `shared/src/androidMain/kotlin/com/lagradost/cloudstream4/compose/Layout.kt` | New upstream file, imported unchanged. |
| `shared/src/androidMain/kotlin/com/lagradost/cloudstream4/theme/ColorPreference.kt` | New upstream file, imported unchanged. |
| `shared/src/androidMain/kotlin/com/lagradost/cloudstream4/theme/DeviceTheme.android.kt` | New upstream file, imported unchanged. |
| `shared/src/androidMain/kotlin/com/mihon/common/preference/AndroidPreference.kt` | New upstream file, imported unchanged. |
| `shared/src/androidMain/kotlin/com/mihon/common/preference/AndroidPreferenceStore.kt` | New upstream file, imported unchanged. |
| `shared/src/androidMain/kotlin/com/mihon/common/preference/DataPreference.kt` | New upstream file, imported unchanged. |
| `shared/src/androidMain/kotlin/com/mihon/common/preference/DataPreferenceStore.kt` | New upstream file, imported unchanged. |
| `shared/src/androidMain/kotlin/com/mihon/common/preference/StatePreferenceStore.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/composeResources/drawable/arrow_back.xml` | New upstream file, imported unchanged. |
| `shared/src/commonMain/composeResources/drawable/arrow_downward.xml` | New upstream file, imported unchanged. |
| `shared/src/commonMain/composeResources/drawable/arrow_upward.xml` | New upstream file, imported unchanged. |
| `shared/src/commonMain/composeResources/drawable/cancel.xml` | New upstream file, imported unchanged. |
| `shared/src/commonMain/composeResources/drawable/check.xml` | New upstream file, imported unchanged. |
| `shared/src/commonMain/composeResources/drawable/close.xml` | New upstream file, imported unchanged. |
| `shared/src/commonMain/composeResources/drawable/default_icon.png` | New upstream file adapted for AdiXtream. |
| `shared/src/commonMain/composeResources/drawable/error.xml` | New upstream file, imported unchanged. |
| `shared/src/commonMain/composeResources/drawable/info.xml` | New upstream file, imported unchanged. |
| `shared/src/commonMain/composeResources/drawable/preview.xml` | New upstream file, imported unchanged. |
| `shared/src/commonMain/composeResources/font/productsans_black.ttf` | New upstream file, imported unchanged. |
| `shared/src/commonMain/composeResources/font/productsans_blackitalic.ttf` | New upstream file, imported unchanged. |
| `shared/src/commonMain/composeResources/font/productsans_bold.ttf` | New upstream file, imported unchanged. |
| `shared/src/commonMain/composeResources/font/productsans_bolditalic.ttf` | New upstream file, imported unchanged. |
| `shared/src/commonMain/composeResources/font/productsans_italic.ttf` | New upstream file, imported unchanged. |
| `shared/src/commonMain/composeResources/font/productsans_light.ttf` | New upstream file, imported unchanged. |
| `shared/src/commonMain/composeResources/font/productsans_lightitalic.ttf` | New upstream file, imported unchanged. |
| `shared/src/commonMain/composeResources/font/productsans_medium.ttf` | New upstream file, imported unchanged. |
| `shared/src/commonMain/composeResources/font/productsans_mediumitalic.ttf` | New upstream file, imported unchanged. |
| `shared/src/commonMain/composeResources/font/productsans_regular.ttf` | New upstream file, imported unchanged. |
| `shared/src/commonMain/composeResources/font/productsans_thin.ttf` | New upstream file, imported unchanged. |
| `shared/src/commonMain/composeResources/font/productsans_thinitalic.ttf` | New upstream file, imported unchanged. |
| `shared/src/commonMain/composeResources/values/strings.xml` | New upstream file adapted for AdiXtream. |
| `shared/src/commonMain/kotlin/com/lagradost/cloudstream4/DevicePreferenceStore.kt` | New upstream file adapted for AdiXtream. |
| `shared/src/commonMain/kotlin/com/lagradost/cloudstream4/compose/ColorDialog.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/lagradost/cloudstream4/compose/Colors.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/lagradost/cloudstream4/compose/Dialog.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/lagradost/cloudstream4/compose/Modifiers.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/lagradost/cloudstream4/compose/Screen.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/lagradost/cloudstream4/compose/Viewmodel.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/lagradost/cloudstream4/compose/colorpicker/CircularColorPicker.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/lagradost/cloudstream4/compose/colorpicker/ColorPickerDefaults.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/lagradost/cloudstream4/compose/colorpicker/DoubleColorPicker.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/lagradost/cloudstream4/compose/colorpicker/HsvColor.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/lagradost/cloudstream4/compose/colorpicker/LICENSE` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/lagradost/cloudstream4/compose/colorpicker/README.md` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/lagradost/cloudstream4/compose/colorpicker/RingColorPicker.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/lagradost/cloudstream4/compose/colorpicker/SquareColorPicker.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/lagradost/cloudstream4/theme/DeviceTheme.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/lagradost/cloudstream4/theme/Font.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/lagradost/cloudstream4/theme/Palette.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/lagradost/cloudstream4/theme/Scheme.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/lagradost/cloudstream4/theme/Theme.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/LICENSE` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/common/preference/InMemoryPreferenceStore.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/common/preference/PreferenceData.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/common/preference/PreferenceStore.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/material/AppBar.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/material/Constants.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/material/LabeledCheckbox.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/material/Pill.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/material/Scaffold.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/material/SettingsItems.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/material/Slider.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/material/Typography.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/presentation/Modifier.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/presentation/Navigation.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/presentation/settings/Preference.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/presentation/settings/PreferenceItem.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/presentation/settings/PreferenceScaffold.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/presentation/settings/PreferenceScreen.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/presentation/settings/Search.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/presentation/settings/SearchableSettings.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/presentation/settings/widget/BasePreferenceWidget.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/presentation/settings/widget/ColorPreferenceWidget.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/presentation/settings/widget/EditTextPreferenceWidget.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/presentation/settings/widget/InfoWidget.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/presentation/settings/widget/ListPreferenceWidget.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/presentation/settings/widget/MultiSelectListPreferenceWidget.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/presentation/settings/widget/PreferenceGroupHeader.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/presentation/settings/widget/SwitchPreferenceWidget.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/presentation/settings/widget/TextPreferenceWidget.kt` | New upstream file, imported unchanged. |
| `shared/src/commonMain/kotlin/com/mihon/readme.md` | New upstream file, imported unchanged. |
| `shared/src/jvmMain/kotlin/com/lagradost/cloudstream4/DevicePreferenceStore.jvm.kt` | New upstream file, imported unchanged. |
| `shared/src/jvmMain/kotlin/com/lagradost/cloudstream4/theme/DeviceTheme.jvm.kt` | New upstream file, imported unchanged. |
| `shared/src/readme.md` | New upstream file, imported unchanged. |

## manual merge (44)

| Path | Decision |
| --- | --- |
| `.github/workflows/buat_apk.yml` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/build.gradle.kts` | Fork diverged; upstream diff ported onto the AdiXtream file. |
| `app/src/main/AndroidManifest.xml` | Fork diverged; upstream diff ported onto the AdiXtream file. |
| `app/src/main/java/com/lagradost/cloudstream3/CloudStreamApp.kt` | Originally identical to baseline; upstream port then adapted to preserve AdiXtream integration. |
| `app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt` | Fork diverged; upstream diff ported onto the AdiXtream file. |
| `app/src/main/java/com/lagradost/cloudstream3/PremiumManager.kt` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/network/RequestsHelper.kt` | Fork diverged; upstream diff ported onto the AdiXtream file. |
| `app/src/main/java/com/lagradost/cloudstream3/plugins/RepositoryManager.kt` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/services/PackageInstallerService.kt` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/syncproviders/providers/AniListApi.kt` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/syncproviders/providers/OpenSubtitlesApi.kt` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/syncproviders/providers/SimklApi.kt` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/account/AccountHelper.kt` | Fork diverged; upstream diff ported onto the AdiXtream file. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/home/HomeFragment.kt` | Fork diverged; upstream diff ported onto the AdiXtream file. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/home/HomeParentItemAdapter.kt` | Fork diverged; upstream diff ported onto the AdiXtream file. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/player/CS3IPlayer.kt` | Fork diverged; upstream diff ported onto the AdiXtream file. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/player/FullScreenPlayer.kt` | Fork diverged; upstream diff ported onto the AdiXtream file. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/player/GeneratorPlayer.kt` | Fork diverged; upstream diff ported onto the AdiXtream file. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/search/SearchFragment.kt` | Fork diverged; upstream diff ported onto the AdiXtream file. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsGeneral.kt` | Fork diverged; upstream diff ported onto the AdiXtream file. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsUI.kt` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/extensions/ExtensionsFragment.kt` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/extensions/ExtensionsViewModel.kt` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/extensions/RepoAdapter.kt` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/setup/SetupFragmentExtensions.kt` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/subtitles/ChromecastSubtitlesFragment.kt` | Fork diverged; upstream diff ported onto the AdiXtream file. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/subtitles/SubtitlesFragment.kt` | Fork diverged; upstream diff ported onto the AdiXtream file. |
| `app/src/main/java/com/lagradost/cloudstream3/utils/AppContextUtils.kt` | Fork diverged; upstream diff ported onto the AdiXtream file. |
| `app/src/main/java/com/lagradost/cloudstream3/utils/InAppUpdater.kt` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/main/java/com/lagradost/cloudstream3/utils/videoskip/AnimeSkip.kt` | Fork diverged; upstream diff ported onto the AdiXtream file. |
| `app/src/main/res/layout/fragment_home.xml` | Fork diverged; upstream diff ported onto the AdiXtream file. |
| `app/src/main/res/layout/fragment_home_tv.xml` | Fork diverged; upstream diff ported onto the AdiXtream file. |
| `app/src/main/res/layout/player_custom_layout.xml` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/main/res/layout/player_custom_layout_tv.xml` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/main/res/layout/trailer_custom_layout.xml` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `app/src/main/res/navigation/mobile_navigation.xml` | Originally identical to baseline; upstream port then adapted to preserve AdiXtream integration. |
| `app/src/main/res/values-b+in/strings.xml` | Fork diverged; upstream diff ported onto the AdiXtream file. |
| `app/src/main/res/values/donottranslate-strings.xml` | Fork diverged; upstream diff ported onto the AdiXtream file. |
| `app/src/main/res/values/strings.xml` | Fork diverged; upstream diff ported onto the AdiXtream file. |
| `app/src/main/res/values/styles.xml` | Fork diverged; upstream diff ported onto the AdiXtream file. |
| `app/src/main/res/xml/settings_ui.xml` | AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream. |
| `library/api/jvm/library.api` | Fork diverged; upstream diff ported onto the AdiXtream file. |
| `library/src/commonMain/kotlin/com/lagradost/cloudstream3/utils/ExtractorApi.kt` | Fork diverged; upstream diff ported onto the AdiXtream file. |
| `settings.gradle.kts` | Originally identical to baseline; upstream port then adapted to preserve AdiXtream integration. |

## protected / intentionally retained (31)

| Path | Decision |
| --- | --- |
| `app/src/main/java/com/lagradost/cloudstream3/CampaignPopupManager.kt` | Protected AdiXtream source retained byte-for-byte. |
| `app/src/main/java/com/lagradost/cloudstream3/PremiumDialogManager.kt` | Protected AdiXtream source retained byte-for-byte. |
| `app/src/main/java/com/lagradost/cloudstream3/RepoProtector.kt` | Protected AdiXtream source retained byte-for-byte. |
| `app/src/main/java/com/lagradost/cloudstream3/SplashActivity.kt` | Protected AdiXtream source retained byte-for-byte. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsFragment.kt` | Protected AdiXtream source retained byte-for-byte. |
| `app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsUpdates.kt` | Protected AdiXtream source retained byte-for-byte. |
| `app/src/main/res/values-b+bn/strings.xml` | Translation/fastlane outside packaged en/id/in locale and AdiXtream branding scope; original retained or upstream-only addition omitted. |
| `app/src/main/res/values-b+cs/strings.xml` | Translation/fastlane outside packaged en/id/in locale and AdiXtream branding scope; original retained or upstream-only addition omitted. |
| `app/src/main/res/values-b+de/strings.xml` | Translation/fastlane outside packaged en/id/in locale and AdiXtream branding scope; original retained or upstream-only addition omitted. |
| `app/src/main/res/values-b+es/strings.xml` | Translation/fastlane outside packaged en/id/in locale and AdiXtream branding scope; original retained or upstream-only addition omitted. |
| `app/src/main/res/values-b+fr/strings.xml` | Translation/fastlane outside packaged en/id/in locale and AdiXtream branding scope; original retained or upstream-only addition omitted. |
| `app/src/main/res/values-b+it/strings.xml` | Translation/fastlane outside packaged en/id/in locale and AdiXtream branding scope; original retained or upstream-only addition omitted. |
| `app/src/main/res/values-b+ja/strings.xml` | Translation/fastlane outside packaged en/id/in locale and AdiXtream branding scope; original retained or upstream-only addition omitted. |
| `app/src/main/res/values-b+ko/strings.xml` | Translation/fastlane outside packaged en/id/in locale and AdiXtream branding scope; original retained or upstream-only addition omitted. |
| `app/src/main/res/values-b+lv/strings.xml` | Translation/fastlane outside packaged en/id/in locale and AdiXtream branding scope; original retained or upstream-only addition omitted. |
| `app/src/main/res/values-b+mk/strings.xml` | Translation/fastlane outside packaged en/id/in locale and AdiXtream branding scope; original retained or upstream-only addition omitted. |
| `app/src/main/res/values-b+pl/strings.xml` | Translation/fastlane outside packaged en/id/in locale and AdiXtream branding scope; original retained or upstream-only addition omitted. |
| `app/src/main/res/values-b+pt+BR/strings.xml` | Translation/fastlane outside packaged en/id/in locale and AdiXtream branding scope; original retained or upstream-only addition omitted. |
| `app/src/main/res/values-b+ru/strings.xml` | Translation/fastlane outside packaged en/id/in locale and AdiXtream branding scope; original retained or upstream-only addition omitted. |
| `app/src/main/res/values-b+so/strings.xml` | Translation/fastlane outside packaged en/id/in locale and AdiXtream branding scope; original retained or upstream-only addition omitted. |
| `app/src/main/res/values-b+sq/strings.xml` | Translation/fastlane outside packaged en/id/in locale and AdiXtream branding scope; original retained or upstream-only addition omitted. |
| `app/src/main/res/values-b+sv/strings.xml` | Translation/fastlane outside packaged en/id/in locale and AdiXtream branding scope; original retained or upstream-only addition omitted. |
| `app/src/main/res/values-b+tr/strings.xml` | Translation/fastlane outside packaged en/id/in locale and AdiXtream branding scope; original retained or upstream-only addition omitted. |
| `app/src/main/res/values-b+uk/strings.xml` | Translation/fastlane outside packaged en/id/in locale and AdiXtream branding scope; original retained or upstream-only addition omitted. |
| `app/src/main/res/values-b+vi/strings.xml` | Translation/fastlane outside packaged en/id/in locale and AdiXtream branding scope; original retained or upstream-only addition omitted. |
| `app/src/main/res/values-b+zh/strings.xml` | Translation/fastlane outside packaged en/id/in locale and AdiXtream branding scope; original retained or upstream-only addition omitted. |
| `app/src/main/res/values-lo/strings.xml` | Translation/fastlane outside packaged en/id/in locale and AdiXtream branding scope; original retained or upstream-only addition omitted. |
| `fastlane/metadata/android/lo-LA/changelogs/2.txt` | Translation/fastlane outside packaged en/id/in locale and AdiXtream branding scope; original retained or upstream-only addition omitted. |
| `fastlane/metadata/android/lo-LA/full_description.txt` | Translation/fastlane outside packaged en/id/in locale and AdiXtream branding scope; original retained or upstream-only addition omitted. |
| `fastlane/metadata/android/lo-LA/short_description.txt` | Translation/fastlane outside packaged en/id/in locale and AdiXtream branding scope; original retained or upstream-only addition omitted. |
| `fastlane/metadata/android/lo-LA/title.txt` | Translation/fastlane outside packaged en/id/in locale and AdiXtream branding scope; original retained or upstream-only addition omitted. |
