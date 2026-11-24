"""UI notifiers for emergency alert countdowns.

Provides multiple notifier styles that can be wired into the alert sender's
``notifier`` hook. Styles include:

- ``tick`` (default): Classic 15-tick countdown, prints every second
- ``pulse``: Emphasizes urgency with "now" on the 3-1 second window
- ``fade``: Only interrupts when countdown drops below 10s (silent mode)
- ``progressive``: Starts subtle and grows louder as time runs out

Each notifier receives ``remaining`` seconds (15 down to 1) and is thread-safe.
"""

from __future__ import annotations

import time
from typing import Callable, Optional


class TickNotifier:
    """Classic tick-by-tick countdown. Prints every second."""

    @staticmethod
    def notify(remaining: int) -> None:
        print(f"[blackbox] sending emergency alert in {remaining}s - cancel now")


class PulseNotifier:
    """Emphasizes urgency with 'now' on the 3-1 second window."""

    @staticmethod
    def notify(remaining: int) -> None:
        if remaining <= 3:
            print(f"[blackbox] NOW {remaining}s - cancel now!")
        else:
            print(f"[blackbox] sending emergency alert in {remaining}s - cancel now")


class FadeNotifier:
    """Silent mode: only interrupts when countdown drops below 10s of 15s."""

    @staticmethod
    def notify(remaining: int) -> None:
        if remaining <= 10:
            print(f"[blackbox] alert in {remaining}s - cancel now")
        else:
            # Subtle hint during the "safe" countdown period
            print(f"[blackbox] ... {remaining}s ...")


class ProgressiveNotifier:
    """Starts subtle and grows louder as time runs out."""

    @staticmethod
    def notify(remaining: int) -> None:
        if remaining >= 12:
            print(f"[blackbox] alert incoming...")
        elif remaining >= 8:
            print(f"[blackbox] alert in {remaining}s...")
        elif remaining >= 4:
            print(f"[blackbox] urgency! {remaining}s!")
        elif remaining >= 2:
            print(f"[blackbox] NOW {remaining}s!")
        else:
            print(f"[blackbox] FINAL {remaining}s - cancel now!")


def _default_notifier(remaining: int) -> None:
    """Default visible countdown (the real UI overrides this)."""
    print(f"[blackbox] sending emergency alert in {remaining}s - cancel now")


def _silent_mode_notifier(remaining: int, threshold: int = 10) -> None:
    """Silent mode notifier that only interrupts when countdown exceeds threshold."""
    if remaining <= threshold:
        print(f"[blackbox] alert in {remaining}s - cancel now")
    else:
        print(f"[blackbox] ... {remaining}s ...")


def _fade_in_out_notifier(remaining: int) -> None:
    """Fade in/out using CSS-style transitions via the notifier hook."""
    # Simulate a fade by printing with varying intensity
    intensity = min(remaining, 15) / 15.0
    prefix = "" if intensity < 0.3 else "." * int(intensity * 10)
    print(f"{prefix}[blackbox] alert in {remaining}s - cancel now")


def _build_countdown_style(style: str = "tick") -> Callable[[int], None]:
    """Factory to build a notifier based on style string."""
    notifiers = {
        "tick": TickNotifier.notify,
        "pulse": PulseNotifier.notify,
        "fade": FadeNotifier.notify,
        "progressive": ProgressiveNotifier.notify,
        "silent": _silent_mode_notifier,
        "fade_in_out": _fade_in_out_notifier,
        "default": _default_notifier,
    }
    return notifiers.get(style.lower(), _default_notifier)
