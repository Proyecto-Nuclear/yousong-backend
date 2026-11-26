"""Local Spark session: UTC everywhere, no UI, small shuffles (one establishment-sized batch)."""

import os
import sys
import time

from pyspark.sql import SparkSession


def create_spark(app_name: str = "yousong-analytics") -> SparkSession:
    # Python workers must interpret timestamps in UTC exactly like the JVM session.
    os.environ["TZ"] = "UTC"
    time.tzset()
    os.environ.setdefault("PYSPARK_PYTHON", sys.executable)
    os.environ.setdefault("PYSPARK_DRIVER_PYTHON", sys.executable)
    return (SparkSession.builder.appName(app_name)
            .master(os.environ.get("ANALYTICS_SPARK_MASTER", "local[2]"))
            .config("spark.sql.session.timeZone", "UTC")
            .config("spark.driver.extraJavaOptions", "-Duser.timezone=UTC")
            .config("spark.executor.extraJavaOptions", "-Duser.timezone=UTC")
            .config("spark.sql.shuffle.partitions", "4")
            .config("spark.ui.enabled", "false")
            .config("spark.ui.showConsoleProgress", "false")
            .config("spark.sql.parquet.compression.codec", "snappy")
            .getOrCreate())
