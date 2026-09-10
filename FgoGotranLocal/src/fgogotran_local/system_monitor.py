from __future__ import annotations

import asyncio
import ipaddress
import os
import time
from contextlib import suppress
from typing import Any

import psutil


GPU_CACHE_SECONDS = 2.0


class SystemMonitor:
    def __init__(self) -> None:
        self._cached_gpu: dict[str, Any] | None = None
        self._cached_gpu_at = 0.0
        self._gpu_lock = asyncio.Lock()

    async def snapshot(self) -> dict[str, Any]:
        now = time.monotonic()
        if now - self._cached_gpu_at >= GPU_CACHE_SECONDS:
            async with self._gpu_lock:
                if now - self._cached_gpu_at >= GPU_CACHE_SECONDS:
                    self._cached_gpu = await self._query_nvidia_gpu()
                    self._cached_gpu_at = time.monotonic()
        memory = psutil.virtual_memory()
        return {
            "gpu": self._cached_gpu,
            "memory": {
                "usedMiB": round(memory.used / 1024 / 1024),
                "totalMiB": round(memory.total / 1024 / 1024),
            },
            "lanAddresses": private_lan_addresses(),
        }

    @staticmethod
    async def _query_nvidia_gpu() -> dict[str, Any] | None:
        executable = "nvidia-smi.exe" if os.name == "nt" else "nvidia-smi"
        process: asyncio.subprocess.Process | None = None
        try:
            process = await asyncio.create_subprocess_exec(
                executable,
                "--query-gpu=name,utilization.gpu,memory.used,memory.total,temperature.gpu",
                "--format=csv,noheader,nounits",
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.DEVNULL,
                creationflags=_hidden_process_flags(),
            )
            stdout, _ = await asyncio.wait_for(process.communicate(), timeout=2.5)
            if process.returncode != 0:
                return None
            line = next((item.strip() for item in stdout.decode("utf-8", errors="replace").splitlines() if item.strip()), "")
            if not line:
                return None
            name, utilization, memory_used, memory_total, temperature = [part.strip() for part in line.split(",", 4)]
            return {
                "name": name,
                "utilizationPercent": _finite_number(utilization),
                "memoryUsedMiB": _finite_number(memory_used),
                "memoryTotalMiB": _finite_number(memory_total),
                "temperatureC": _finite_number(temperature),
            }
        except asyncio.TimeoutError:
            if process and process.returncode is None:
                process.kill()
                with suppress(asyncio.CancelledError):
                    await process.wait()
            return None
        except (OSError, ValueError):
            return None


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


def _finite_number(value: str) -> float | None:
    try:
        return float(value)
    except ValueError:
        return None


def _hidden_process_flags() -> int:
    if os.name != "nt":
        return 0
    import subprocess

    return subprocess.CREATE_NO_WINDOW
