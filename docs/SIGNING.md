# Permanent signing (so new builds install over old ones)

Android only lets an APK update an installed app if both are signed with the **same key**. The workflow signs every build with
a permanent key kept in GitHub **Secrets** (never in the repository — it is public).

## One-time setup (Termux)
1. Put `zara-signing.p12`, `zara-signing.pass` and `zara-signing-setup.sh` in your phone's Downloads folder.
2. In Termux: `bash ~/storage/downloads/zara-signing-setup.sh` and paste your GitHub token when asked (it is not shown).
   This saves two repository secrets: `ZARA_KEYSTORE_B64` and `ZARA_KEY_PASSWORD`.
3. Back up the `.p12` and `.pass` files somewhere private, then delete them from Downloads.
4. Push a commit (or re-run the workflow). In the run log, the step **Show signing certificate fingerprint** prints the key's
   SHA-256 — it must be identical on every build.
5. **Uninstall the old app once** (it was signed with a temporary key). From then on, new APKs install over the old ones and
   keep your settings.

## Notes
- If you lose the key you can't update the installed app any more (you would reinstall once with a new key).
- Without the secrets the workflow still builds, but warns and signs with a throwaway key.
- Optional stronger setup: generate your own key on the phone (`pkg install openjdk-17`, then `keytool -genkeypair -storetype PKCS12 ...
  -alias zara`) and store *that* in the same two secrets.
