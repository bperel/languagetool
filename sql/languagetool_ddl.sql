/*!40101 SET @OLD_CHARACTER_SET_CLIENT = @@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS = @@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION = @@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE = @@TIME_ZONE */;
/*!40103 SET TIME_ZONE = '+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS = @@UNIQUE_CHECKS, UNIQUE_CHECKS = 0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS = @@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS = 0 */;
/*!40101 SET @OLD_SQL_MODE = @@SQL_MODE, SQL_MODE = 'NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES = @@SQL_NOTES, SQL_NOTES = 0 */;

--
-- Table structure for table `access_token`
--

DROP TABLE IF EXISTS `access_token`;
/*!40101 SET @saved_cs_client = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `access_token`
(
    `ID`                  int(11)      NOT NULL AUTO_INCREMENT,
    `language_code`       varchar(15)  NOT NULL,
    `username`            varchar(255) NOT NULL,
    `access_token`        varchar(255) NOT NULL,
    `access_token_secret` varchar(255) NOT NULL,
    PRIMARY KEY (`ID`),
    UNIQUE KEY `access_token_access_token_uindex` (`language_code`, `access_token`)
) ENGINE = InnoDB
  DEFAULT CHARSET = latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `corpus_article`
--

DROP TABLE IF EXISTS `corpus_article`;
/*!40101 SET @saved_cs_client = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `corpus_article`
(
    `id`              int(11)      NOT NULL AUTO_INCREMENT,
    `language_code`   varchar(15)  NOT NULL,
    `title`           varchar(255) NOT NULL,
    `revision`        int(11)      NOT NULL,
    `wikitext`        mediumtext    DEFAULT NULL,
    `html`            mediumtext    DEFAULT NULL,
    `anonymized_html` mediumtext   NOT NULL,
    `css_url`         varchar(1023) DEFAULT NULL,
    `analyzed`        tinyint(1)   NOT NULL,
    `url`             varchar(300) GENERATED ALWAYS AS (concat('https://', `language_code`, '.wikipedia.org/wiki/', `title`)) STORED,
    PRIMARY KEY (`id`),
    UNIQUE KEY `corpus_article_uindex` (`title`, `revision`),
    KEY `corpus_article_language_code_index` (`language_code`),
    KEY `corpus_article_url_index` (`url`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `corpus_match`
--

DROP TABLE IF EXISTS `corpus_match`;
/*!40101 SET @saved_cs_client = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `corpus_match`
(
    `id`                     int(11)      NOT NULL AUTO_INCREMENT,
    `article_id`             int(11)      NOT NULL,
    `article_language_code`  varchar(15)  NOT NULL,
    `ruleid`                 varchar(255) NOT NULL,
    `rule_category`          varchar(255) NOT NULL,
    `rule_subid`             varchar(16)           DEFAULT NULL,
    `rule_description`       varchar(255) NOT NULL,
    `message`                varchar(255) NOT NULL,
    `error_context`          mediumtext   NOT NULL,
    `small_error_context`    mediumtext   NOT NULL,
    `html_error_context`     mediumtext            DEFAULT NULL,
    `replacement_suggestion` varchar(255) NOT NULL,
    `applied`                tinyint(1)            DEFAULT NULL,
    `applied_date`           datetime              DEFAULT NULL,
    `applied_reason`         varchar(31)           DEFAULT NULL,
    `applied_username`       varchar(255)          DEFAULT NULL,
    `languagetool_version`   varchar(5)   NOT NULL DEFAULT '4.9.1',
    PRIMARY KEY (`id`),
    UNIQUE KEY `corpus_match_unique` (`article_id`, `rule_description`, `error_context`,
                                      `replacement_suggestion`) USING HASH,
    KEY `corpus_match_index_article` (`article_id`),
    KEY `corpus_match_applied_date_index` (`applied_date`),
    KEY `corpus_match_rule_article_id_version_index` (`rule_category`, `rule_description`, `languagetool_version`,
                                                      `article_id`),
    KEY `corpus_match_applied_index` (`applied`),
    KEY `corpus_match_article_applied_index` (`article_id`, `applied`),
    KEY `corpus_match_rule_applied_index` (`ruleid`, `rule_subid`, `applied`),
    KEY `corpus_match_applied_article_language_code_index` (`applied`, `article_language_code`),
    KEY `corpus_match_article_language_code_index` (`article_language_code`),
    CONSTRAINT `corpus_match_corpus_article_id_fk` FOREIGN KEY (`article_id`) REFERENCES `corpus_article` (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `corpus_match_skipped`
--

DROP TABLE IF EXISTS `corpus_match_skipped`;
/*!40101 SET @saved_cs_client = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `corpus_match_skipped`
(
    `corpus_match_id` int(11)      NOT NULL,
    `date`            datetime     NOT NULL,
    `username`        varchar(255) NOT NULL,
    PRIMARY KEY (`corpus_match_id`, `username`),
    CONSTRAINT `corpus_match_skipped_corpus_match_id_fk` FOREIGN KEY (`corpus_match_id`) REFERENCES `corpus_match` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_ignored_rules`
--

DROP TABLE IF EXISTS `user_ignored_rules`;
/*!40101 SET @saved_cs_client = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `user_ignored_rules`
(
    `language_code` varchar(15)  NOT NULL,
    `username`      varchar(255) NOT NULL,
    `ruleid`        varchar(255) NOT NULL,
    PRIMARY KEY (`language_code`, `username`, `ruleid`)
) ENGINE = InnoDB
  DEFAULT CHARSET = latin1;

/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE = @OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE = @OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS = @OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS = @OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT = @OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS = @OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION = @OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES = @OLD_SQL_NOTES */;
