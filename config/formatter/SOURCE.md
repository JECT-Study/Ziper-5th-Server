# Project formatter provenance

This is a project-authored, minimal Eclipse JDT preset based on NAVER's documented
Java conventions: hard tabs with width four, two-level continuation indentation,
120-column lines, opening braces on the declaration line, and blank lines after
imports. It is not a copy of the upstream formatter XML.

Convention reference pinned to commit e12658edad4017e2c00ef560704129901770cc2b:
https://github.com/naver/hackday-conventions-java/blob/e12658edad4017e2c00ef560704129901770cc2b/src/java-rules.adoc

The upstream repository has no explicit license at this commit, so its formatter
XML is not redistributed here. The preset retains the agreed filename for the
Gradle and IDE configuration. Checkstyle enforces import hygiene separately.
