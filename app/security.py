"""URL validation and SSRF protection for media extraction."""

import ipaddress
import socket
from urllib.parse import urlparse


class UnsafeURLError(ValueError):
    """Raised when a URL is malformed or points to a restricted/private address."""


def _is_disallowed_ip(ip_str: str) -> bool:
    ip = ipaddress.ip_address(ip_str)
    return (
        ip.is_private
        or ip.is_loopback
        or ip.is_link_local
        or ip.is_multicast
        or ip.is_reserved
        or ip.is_unspecified
    )


def validate_public_url(raw_url: str) -> str:
    """Validate that raw_url is a public HTTP(S) URL and does not resolve to a private IP."""
    url = (raw_url or "").strip()
    if not url:
        raise UnsafeURLError("URL cannot be empty.")

    parsed = urlparse(url)
    if parsed.scheme.lower() not in ("http", "https"):
        raise UnsafeURLError("Only http and https URLs are supported.")

    hostname = parsed.hostname
    if not hostname:
        raise UnsafeURLError("URL must include a valid hostname.")

    lower_host = hostname.lower()
    if lower_host in ("localhost", "localhost.localdomain") or lower_host.endswith(".local"):
        raise UnsafeURLError("Localhost and .local addresses are not allowed.")

    # Direct IP literal check
    try:
        if _is_disallowed_ip(hostname):
            raise UnsafeURLError("Private or reserved IP addresses are not allowed.")
        return url
    except ValueError:
        pass

    # Resolve hostname and verify all returned IPs are public
    try:
        addr_info = socket.getaddrinfo(hostname, parsed.port or (443 if parsed.scheme == "https" else 80))
    except socket.gaierror as exc:
        raise UnsafeURLError(f"Could not resolve hostname '{hostname}'.") from exc

    if not addr_info:
        raise UnsafeURLError(f"No DNS records found for '{hostname}'.")

    for entry in addr_info:
        ip_str = entry[4][0]
        # Strip IPv6 scope ID if present
        ip_clean = ip_str.split("%", 1)[0]
        if _is_disallowed_ip(ip_clean):
            raise UnsafeURLError("Target hostname resolves to a private or reserved IP address.")

    return url
