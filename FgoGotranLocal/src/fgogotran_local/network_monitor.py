from __future__ import annotations

import ipaddress

import psutil


class NetworkMonitor:
    """Collect only the LAN address needed to build the phone endpoint.

    Hardware details are intentionally not collected by the web service. Platform
    setup scripts may still detect CUDA privately when selecting a compatible
    llama.cpp package.
    """

    async def snapshot(self) -> dict[str, list[str]]:
        return {"lanAddresses": private_lan_addresses()}


def private_lan_addresses() -> list[str]:
    addresses: set[str] = set()
    for entries in psutil.net_if_addrs().values():
        for entry in entries:
            if entry.family.name != "AF_INET":
                continue
            try:
                address = ipaddress.ip_address(entry.address)
            except ValueError:
                continue
            if _is_private_rfc1918(address):
                addresses.add(str(address))
    return sorted(addresses, key=_private_address_rank)


def _is_private_rfc1918(address: ipaddress.IPv4Address) -> bool:
    return any(address in network for network in (
        ipaddress.ip_network("10.0.0.0/8"),
        ipaddress.ip_network("172.16.0.0/12"),
        ipaddress.ip_network("192.168.0.0/16"),
    ))


def _private_address_rank(address: str) -> tuple[int, str]:
    if address.startswith("192.168."):
        return 0, address
    if address.startswith("10."):
        return 1, address
    return 2, address
