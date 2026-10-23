package kg.dental.patient;

import org.springframework.data.jpa.repository.JpaRepository;

interface TelegramContactRepository extends JpaRepository<TelegramContact, Long> {
}
