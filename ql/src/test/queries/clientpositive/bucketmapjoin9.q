--! qt:dataset:part

SET hive.vectorized.execution.enabled=false;
set hive.strict.checks.bucketing=false;

set hive.input.format=org.apache.hadoop.hive.ql.io.HiveInputFormat;

CREATE TABLE srcbucket_mapjoin_part_1_n5 (key INT, value STRING) PARTITIONED BY (part STRING) 
CLUSTERED BY (key) INTO 2 BUCKETS STORED AS TEXTFILE;
LOAD DATA LOCAL INPATH '../../data/files/bmj/000000_0' INTO TABLE srcbucket_mapjoin_part_1_n5 PARTITION (part='1');
LOAD DATA LOCAL INPATH '../../data/files/bmj/000001_0' INTO TABLE srcbucket_mapjoin_part_1_n5 PARTITION (part='1');

CREATE TABLE srcbucket_mapjoin_part_2_n12 (key INT, value STRING) PARTITIONED BY (part STRING) 
CLUSTERED BY (key) INTO 3 BUCKETS STORED AS TEXTFILE;
LOAD DATA LOCAL INPATH '../../data/files/bmj/000000_0' INTO TABLE srcbucket_mapjoin_part_2_n12 PARTITION (part='1');
LOAD DATA LOCAL INPATH '../../data/files/bmj/000001_0' INTO TABLE srcbucket_mapjoin_part_2_n12 PARTITION (part='1');
LOAD DATA LOCAL INPATH '../../data/files/bmj/000002_0' INTO TABLE srcbucket_mapjoin_part_2_n12 PARTITION (part='1');

ALTER TABLE srcbucket_mapjoin_part_2_n12 CLUSTERED BY (key) INTO 2 BUCKETS;

set hive.optimize.bucketmapjoin=true;

set hive.cbo.enable=false;
-- The table bucketing metadata matches but the partitions have different numbers of buckets, bucket map join should not be used

EXPLAIN EXTENDED
SELECT /*+ MAPJOIN(b) */ count(*)
FROM srcbucket_mapjoin_part_1_n5 a JOIN srcbucket_mapjoin_part_2_n12 b
ON a.key = b.key AND a.part = '1' and b.part = '1';

SELECT /*+ MAPJOIN(b) */ count(*)
FROM srcbucket_mapjoin_part_1_n5 a JOIN srcbucket_mapjoin_part_2_n12 b
ON a.key = b.key AND a.part = '1' and b.part = '1';

ALTER TABLE srcbucket_mapjoin_part_2_n12 DROP PARTITION (part='1');
ALTER TABLE srcbucket_mapjoin_part_2_n12 CLUSTERED BY (value) INTO 2 BUCKETS;
LOAD DATA LOCAL INPATH '../../data/files/bmj/000000_0' INTO TABLE srcbucket_mapjoin_part_2_n12 PARTITION (part='1');
LOAD DATA LOCAL INPATH '../../data/files/bmj/000001_0' INTO TABLE srcbucket_mapjoin_part_2_n12 PARTITION (part='1');

ALTER TABLE srcbucket_mapjoin_part_2_n12 CLUSTERED BY (key) INTO 2 BUCKETS;

-- The table bucketing metadata matches but the partitions are bucketed on different columns, bucket map join should not be used

EXPLAIN EXTENDED
SELECT /*+ MAPJOIN(b) */ count(*)
FROM srcbucket_mapjoin_part_1_n5 a JOIN srcbucket_mapjoin_part_2_n12 b
ON a.key = b.key AND a.part = '1' AND b.part = '1';

SELECT /*+ MAPJOIN(b) */ count(*)
FROM srcbucket_mapjoin_part_1_n5 a JOIN srcbucket_mapjoin_part_2_n12 b
ON a.key = b.key AND a.part = '1' AND b.part = '1';
