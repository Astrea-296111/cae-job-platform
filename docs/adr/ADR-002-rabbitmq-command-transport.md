# ADR-002: RabbitMQ for durable commands

Accepted for the public reproduction. MySQL owns business state; RabbitMQ transports bounded submission, parsing, post and report commands. Consumers ACK after durable business acceptance, never wait for a Solver to finish.

RabbitMQ provides persistent queues/messages, publisher confirms, mandatory returns, manual ACK, delayed retry queues and dead letters. These fit independent work commands and explicit retry budgets. Kafka's replayable stream and partition ordering do not remove our database/remote-process idempotency requirements and add an unnecessary operational dependency here.

The database transaction writes state/events/Outbox together. A leased publisher sends outside the transaction and records the correlated broker confirmation. A crash between confirmation and recording can redeliver. Inbox leases and unique logical identities make that safe; this is at-least-once delivery. UNKNOWN execution identity retains capacity until reconciliation confirms the remote fact. See [delivery implementation and recovery](../mq-and-outbox.md).

Exchange/queue declarations and consumers are real Spring AMQP code, tested against the actual Compose broker. Infrastructure failures retain deliveries; bounded business failures reach the DLQ. Tests create owned virtual hosts rather than purging application queues.
