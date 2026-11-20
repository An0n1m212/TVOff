"""Логика выключения Google TV / Android TV (библиотека androidtvremote2)."""
import asyncio
import os
import socket

from androidtvremote2 import AndroidTVRemote, InvalidAuth

PORT = 6466  # порт Android TV Remote. Задан самим телевизором и не меняется

_loop = asyncio.new_event_loop()
_remote = None
_dir = ""


def _run(coro):
    return _loop.run_until_complete(coro)


def _local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    finally:
        s.close()


async def _is_open(ip, timeout=0.4):
    try:
        _, w = await asyncio.wait_for(asyncio.open_connection(ip, PORT), timeout)
        w.close()
        return True
    except Exception:
        return False


async def _find():
    ipfile = os.path.join(_dir, "ip")
    if os.path.exists(ipfile):  # сначала запомненный IP
        ip = open(ipfile).read().strip()
        if await _is_open(ip):
            return ip
    base = _local_ip().rsplit(".", 1)[0]  # иначе скан подсети
    ips = [f"{base}.{i}" for i in range(1, 255)]
    res = await asyncio.gather(*(_is_open(i) for i in ips))
    found = [i for i, ok in zip(ips, res) if ok]
    if found:
        open(ipfile, "w").write(found[0])
        return found[0]
    return None


async def _power(r):
    await asyncio.sleep(0.7)  # дать ТВ прислать своё состояние
    if r.is_on is False:  # POWER на выключенном ТВ включил бы его
        return "already_off"
    r.send_key_command("POWER")
    await asyncio.sleep(0.5)
    return "ok"


def power_off(files_dir):
    """Возвращает: ok | already_off | notfound | pair"""
    global _remote, _dir
    _dir = files_dir
    ip = _run(_find())
    if not ip:
        return "notfound"
    r = AndroidTVRemote("TV Off", os.path.join(_dir, "cert.pem"),
                        os.path.join(_dir, "key.pem"), ip)
    _run(r.async_generate_cert_if_missing())
    try:
        _run(r.async_connect())
    except InvalidAuth:  # первый запуск: ТВ покажет код
        _run(r.async_start_pairing())
        _remote = r
        return "pair"
    res = _run(_power(r))
    r.disconnect()
    return res


def finish_pairing(code):
    r = _remote
    _run(r.async_finish_pairing(code.strip().upper()))
    _run(r.async_connect())
    res = _run(_power(r))
    r.disconnect()
    return res
