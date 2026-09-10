# Security

FgoGotran Local separates the local control interface from the translation API. The control interface always listens on loopback. Each model profile may expose the translation API either to the same computer only or to the trusted local network.

## Built-in protections

- A random API key is generated during first initialization.
- The key is passed to llama.cpp through a temporary API-key file instead of a command-line argument.
- The temporary key file is removed after the managed llama-server stops.
- The control server accepts only loopback host names.
- State-changing control requests require the expected local header, JSON content type, same-origin checks, and a bounded request size.
- The llama.cpp built-in Web UI is disabled.
- llama.cpp is started in offline mode and does not download models.
- Configuration is stored in `user_data/config.json`; a backup is retained before replacement.
- Runtime logs are kept in memory and redact the API key.
- Translation prompts and game dialogue are not intentionally written to the control log.
- No UPnP rule, router port forwarding, or public Internet listener is created.

## Network modes

- `127.0.0.1`: translation is available only on the PC.
- `0.0.0.0`: translation is available through the PC's LAN addresses and requires Windows Firewall to permit the inference process.

Binding to `0.0.0.0` does not by itself publish the service on the Internet. Router port forwarding, an unsafe VPN route, or an incorrectly scoped firewall rule can still expose it beyond the intended network.

## User responsibilities

- Download llama.cpp and GGUF files only from trusted publishers.
- Verify checksums or signatures when the publisher provides them.
- Use LAN mode only on a trusted home or private network.
- Allow the service only on the Windows private-network profile.
- Do not share the API key, `user_data`, or diagnostics containing private paths.
- Never forward ports `18080` or `18081` on the router.
- Do not use the service on a public Wi-Fi network.
- Review VPN, virtual-machine, and container routes if they bridge the LAN interface.

## If the API key may be exposed

1. Stop llama-server from the overview page.
2. Select the API-key rotation confirmation.
3. Rotate the key.
4. Replace the old key in FgoGotran on every phone that uses the service.
5. Restart the translation service.

The old key becomes invalid immediately after rotation.

## Sensitive files

Do not publish or attach these paths:

- `user_data/config.json`
- `user_data/config.json.bak`
- `user_data/state/`
- Any copied runtime log that contains private filesystem paths

The repository `.gitignore` excludes the default sensitive and large-file locations, but users should still inspect staged files before committing.
