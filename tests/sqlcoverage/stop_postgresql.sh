#!/bin/bash

# This script stops a PostgreSQL server. It assumes that you are running on one
# of the Jenkins machines, as USER 'test'; failing that, it may not work, so
# user beware. Typical use would be to run the start_postgresql.sh script first,
# then a set of SqlCoverage tests, and then this script. Note that both PG_PATH
# and PG_TMP_DIR must be set before running this, which they will be, if
# start_postgresql.sh was run first, using 'source' or '.'.

# Stop the PostgreSQL server & delete the temp directory, but save the log file(s)
export ORIG_DIR=`pwd`
echo  "ORIG_DIR:" $ORIG_DIR
ps -ef | grep -i postgres
$PG_PATH/pg_ctl stop -D $PG_TMP_DIR/data
code1=$?
ls -l $PG_TMP_DIR/postgres.log
cat   $PG_TMP_DIR/postgres.log
mv    $PG_TMP_DIR/postgres.log $ORIG_DIR
# On some machines (CentOS) the log is in a different directory
mv    $PG_TMP_DIR/data/pg_log/postgres*.log $ORIG_DIR
rm -r $PG_TMP_DIR
code2=$?

code=$(($code1|$code2))
echo "code1 code2:" $code1 $code2
echo "code:" $code
exit $code
