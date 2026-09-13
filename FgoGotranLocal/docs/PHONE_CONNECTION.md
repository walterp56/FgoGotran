# Phone Connection

## Use the correct address

The control interface at `http://127.0.0.1:18081` is intentionally limited to the PC. A phone must use the translation Endpoint shown after clicking **显示 Endpoint** on the overview page, for example:

```text
http://<PC-LAN-IP>:18080/v1/chat/completions
```

The corresponding health address is:

```text
http://<PC-LAN-IP>:18080/health
```

The router address is not the PC address. Always reveal and copy the current address from FgoGotran Local.

## Connection checklist

1. Connect the phone and PC to the same trusted Wi-Fi or LAN.
2. Set the current Windows network profile to **Private**.
3. Select trusted-LAN plus local access in the active model profile.
4. Save the profile and restart llama-server if it was already running.
5. Wait until the server state is ready.
6. If Windows Firewall prompts, allow the inference executable only on private networks.
7. Click **显示 Endpoint**, then open the corresponding health address in the phone browser.
8. Enter the complete Endpoint, identical Model ID, and the API Key shown after clicking **显示 API Key** in FgoGotran.

The Android app accepts cleartext HTTP only for numeric private-LAN addresses. Do not enter `localhost`, a PC host name, or a public HTTP address.

## If the health page keeps loading

- Confirm that the active profile uses `0.0.0.0`, not `127.0.0.1`.
- Confirm that llama-server is ready rather than still loading or stopped.
- Recheck the PC LAN address; do not use the router address.
- Temporarily disconnect VPN software that blocks local-LAN traffic, or enable its LAN-access option.
- Check that guest Wi-Fi or access-point isolation is not separating the phone from the PC.
- Review the Windows Firewall private-network rule for the selected `llama-server.exe`.
- Confirm that another process is not using the configured inference port.

Do not solve a LAN failure by opening a router port or disabling the firewall globally.

## Address changes

A router may assign a different PC address after reconnecting. If a previously working connection fails, copy the current Endpoint again. For a stable LAN address, configure a DHCP reservation for the PC in the router. A DHCP reservation is not the same as public port forwarding.

## Local-only mode

Select `127.0.0.1` when only software on the PC needs the API. In this mode, the phone cannot connect by design.
