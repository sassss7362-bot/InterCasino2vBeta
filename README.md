# InterTime Casino (Paper 1.21.x, Java 21)

Збірка: `mvn clean package` -> target/InterCasino.jar -> у папку plugins.

Команди:
  /casino            - головне меню (6 ігор)
  /casino rules      - книга з правилами
  /casino balance    - баланс монет
  /casino give|take <гравець> <сума>  (intertime.casino.admin)

Ігри: Рулетка блоків, Орел і Решка, Кубик, Три скрині, Слоти, Шахта.
Валюти ставок: монети, алмази, незерит. Обмін у головному меню.
Налаштування: config.yml
