#!/usr/bin/env bash
set -e

git checkout work -- hakyll/
git reset
pushd hakyll
stack build
stack exec site build
popd
mv hakyll/_site/* .
rm -r hakyll/
git add .
git rev-parse work >.git/MERGE_HEAD
git commit -m "Merge branch 'work' into master"
