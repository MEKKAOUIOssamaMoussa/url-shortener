create table click_stats (
    code varchar(16) not null references short_url(code),
    day date not null,
    count bigint not null,
    primary key (code, day)
);
