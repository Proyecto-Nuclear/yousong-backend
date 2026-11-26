"""Command line: ``python -m yousong_analytics once`` or ``python -m yousong_analytics loop``."""

import argparse
import logging
import sys

from .config import Config, ConfigError


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="yousong_analytics", description="YouSong analytics pipeline")
    parser.add_argument("mode", choices=("once", "loop"),
                        help="once: one cycle and exit; loop: a cycle every ANALYTICS_INTERVAL_SECONDS (300)")
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    logging.getLogger("py4j").setLevel(logging.WARNING)
    try:
        config = Config.from_env()
    except ConfigError as error:
        logging.getLogger("yousong_analytics").error("%s", error)
        return 2

    # Imported late so a configuration error never starts a JVM.
    from .pipeline import run_loop, run_once
    from .spark import create_spark

    spark = create_spark()
    spark.sparkContext.setLogLevel("WARN")
    try:
        if args.mode == "once":
            return 0 if run_once(config, spark).estado in ("PUBLICADO", "VALIDADO", "OCUPADO") else 1
        run_loop(config, spark)
        return 0
    finally:
        spark.stop()


if __name__ == "__main__":
    sys.exit(main())
