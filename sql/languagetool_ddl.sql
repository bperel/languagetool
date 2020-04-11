create table access_token
(
    ID int auto_increment
        primary key,
    language_code varchar(15) not null,
    username varchar(255) not null,
    access_token varchar(255) not null,
    access_token_secret varchar(255) not null,
    constraint access_token_access_token_uindex
        unique (language_code, access_token)
);

create table corpus_article
(
    id int auto_increment
        primary key,
    language_code varchar(15) not null,
    title varchar(255) not null,
    revision int not null,
    wikitext mediumtext null,
    anonymized_html mediumtext not null,
    analyzed tinyint(1) not null,
    url varchar(300) as (concat('https://', `language_code`,'.wikipedia.org/wiki/',`title`)) stored,
    constraint corpus_article_uindex
        unique (title, revision)
)
    charset=utf8mb4;

create table corpus_match
(
    id int auto_increment
        primary key,
    article_id int not null,
    ruleid varchar(255) not null,
    rule_category varchar(255) not null,
    rule_subid varchar(16) null,
    rule_description varchar(255) not null,
    message varchar(255) not null,
    error_context mediumtext not null,
    small_error_context mediumtext not null,
    replacement_suggestion varchar(255) not null,
    applied tinyint(1) null,
    applied_date datetime null,
    applied_reason varchar(31) null,
    applied_username varchar(255) null,
    constraint corpus_match_unique
        unique (article_id, rule_description, error_context, replacement_suggestion) using hash,
    constraint corpus_match_corpus_article_id_fk
        foreign key (article_id) references corpus_article (id)
)
    charset=utf8mb4;

create index corpus_match_index_article
    on corpus_match (article_id);

