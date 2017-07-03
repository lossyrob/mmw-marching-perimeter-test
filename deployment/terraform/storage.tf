#
# S3 resources
#
resource "aws_s3_bucket" "logs" {
  bucket = "rastervision-${lower(var.environment)}-logs-${var.aws_region}"

  tags {
    Project     = "${var.project}"
    Environment = "${var.environment}"
  }
}

resource "aws_s3_bucket" "catalogs" {
  bucket = "rastervision-${lower(var.environment)}-catalogs-${var.aws_region}"

  tags {
    Project     = "${var.project}"
    Environment = "${var.environment}"
  }
}
