# Emulator screenshots

These are actual Android 15 Pixel 2 AVD captures, not mockups. The debug package
is isolated from production customer storage. Device IDs shown belong to the test
emulator. Main menus use the AVD's English locale; Indonesian resources preserve
the requested Umum/Pemutar/Antarmuka pengguna/Update dan Cadangan/Akun dan Keamanan/
Ekstensi labels. The dedicated AdiXtream subscription labels are Indonesian.

## Phone portrait, tested source f4b6fafc

The phone portrait sub-flow reached all menus/build stamp, copied the Device ID,
checked disabled empty-code buttons and reached promo/plans. The containing test
later failed in landscape; see VALIDATION.md. Public QRIS access worked in this
run despite Wi-Fi/data disable commands. No payment, activation or promo was sent.

![Root Settings after scrolling](f4b6fafc/settings-bottom-1-0.png)

![Free status, Device ID copy and activation](f4b6fafc/phone-portrait-subscription-status.png)

![Activation and promo fields](f4b6fafc/phone-portrait-subscription-promo.png)

![Existing QRIS, plans and contact](f4b6fafc/phone-portrait-subscription-plans.png)

Android's clipboard overlay appears in the promo capture immediately after the
copy assertion. A screenshot is evidence of rendering, not proof of production
license, upgrade or backend behavior.

## Phone landscape, tested source e761ba3a

The full phone portrait/landscape test passed on this source. The TV method still
failed a capture-bounds assertion; its prior D-pad actions passed, but the method
is not counted as passed. These selected images cover the verified phone flow.

![Landscape Settings bottom](e761ba3a/settings-bottom-2-0.png)

![Landscape subscription and copy](e761ba3a/phone-landscape-subscription-status.png)

PR-triggered CI checks out GitHub's temporary merge commit, so the on-screen build
stamp may show that merge SHA rather than the migration branch's source SHA.
