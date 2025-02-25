CREATE OR REPLACE FUNCTION getIdFromSupplierName(supplier_name_check varchar(80))
    RETURNS UUID
    LANGUAGE plpgsql
AS
$$
Declare
    supplier_id UUID;
Begin
    SELECT id
    INTO supplier_id
    FROM supplier
    WHERE name = supplier_name_check;
    RETURN supplier_id;
End;
$$;

CREATE OR REPLACE FUNCTION getIdFromAcquirerName(acquirer_name_check varchar(80))
    RETURNS UUID
    LANGUAGE plpgsql
AS
$$
Declare
    acquirer_id UUID;
Begin
    SELECT id
    INTO acquirer_id
    FROM acquirer
    WHERE name = acquirer_name_check;
    RETURN acquirer_id;
End;
$$;

DELETE
FROM acquirer_event
WHERE acquirer_subscription_id IN
      (SELECT id
       FROM acquirer_subscription
       WHERE oauth_client_id IN ('dwp-event-receiver', 'hmrc-client'));

DELETE
FROM acquirer_subscription_enrichment_field
WHERE acquirer_subscription_id IN
      (SELECT id
       FROM acquirer_subscription
       WHERE oauth_client_id IN ('dwp-event-receiver', 'hmrc-client'));

DELETE
FROM supplier_event
WHERE supplier_subscription_id IN
      (SELECT id
       FROM supplier_subscription
       WHERE supplier_id IN (
                             getIdFromSupplierName('HMPO')
           ));

DELETE
FROM supplier_subscription
WHERE supplier_id IN (getIdFromSupplierName('HMPO'));

DELETE
FROM acquirer
WHERE name IN ('DWP Poller', 'Pub/Sub Consumer');

DELETE
FROM supplier
where name IN ('HMPO');

INSERT INTO supplier (name)
VALUES ('HMPO');

INSERT INTO acquirer (name)
VALUES ('DWP Poller'),
       ('Pub/Sub Consumer');

INSERT INTO supplier_subscription
    (client_id, supplier_id, event_type)
VALUES ('len', getIdFromSupplierName('HMPO'), 'DEATH_NOTIFICATION');

INSERT INTO acquirer_subscription
(oauth_client_id, acquirer_id, event_type, enrichment_fields_included_in_poll)
VALUES ('dwp-event-receiver', getIdFromAcquirerName('DWP Poller'), 'DEATH_NOTIFICATION', false),
       ('dwp-event-receiver', getIdFromAcquirerName('DWP Poller'), 'LIFE_EVENT', false),
       ('hmrc-client', getIdFromAcquirerName('Pub/Sub Consumer'), 'DEATH_NOTIFICATION', true),
       ('hmrc-client', getIdFromAcquirerName('Pub/Sub Consumer'), 'LIFE_EVENT', true);

INSERT INTO acquirer_subscription_enrichment_field(acquirer_subscription_id, enrichment_field)
SELECT id,
       unnest(ARRAY ['SOURCE_ID', 'REGISTRATION_DATE', 'FIRST_NAMES', 'LAST_NAME', 'MAIDEN_NAME', 'DATE_OF_DEATH', 'DATE_OF_BIRTH', 'SEX', 'ADDRESS', 'BIRTH_PLACE', 'DEATH_PLACE', 'OCCUPATION', 'RETIRED'])
FROM acquirer_subscription
WHERE event_type = 'DEATH_NOTIFICATION'
  AND oauth_client_id IN ('dwp-event-receiver', 'hmrc-client');

DROP FUNCTION IF EXISTS getIdFromSupplierName;
DROP FUNCTION IF EXISTS getIdFromAcquirerName;
