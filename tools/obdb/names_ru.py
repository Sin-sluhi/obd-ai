"""Русские названия марочных параметров OBDb → app/src/main/assets/obdb/names_ru.json.

    python tools/obdb/names_ru.py

Шаблоны: цифры в названии заменены на #, «#1» у OBDb — на «№#» (так одна строка переводит «Cell voltage 37» и все
остальные ячейки). Числа подставляются обратно по порядку. Чего нет в словаре — остаётся по-английски.
Переведены самые частые шаблоны; дописывать сюда, порядок не важен.
"""
import json, os, re

T = {
    # пробег, скорость, движение
    "Odometer": "Пробег", "Vehicle odometer": "Пробег", "Odometer, metric": "Пробег", "Odometer, imperial": "Пробег (мили)",
    "Odometer #": "Пробег #", "Odometer №#": "Пробег №#",
    "Vehicle speed": "Скорость", "Vehicle speed, high resolution": "Скорость (точно)", "Vehicle speed, v#": "Скорость, вариант #",
    "Vehicle speed, average": "Скорость средняя", "Cruise control speed": "Скорость круиз-контроля",
    "Front left wheel speed": "Скорость колеса, переднее левое", "Front right wheel speed": "Скорость колеса, переднее правое",
    "Rear left wheel speed": "Скорость колеса, заднее левое", "Rear right wheel speed": "Скорость колеса, заднее правое",
    "Anti-lock brake wheel speed #": "Скорость колеса по ABS #",
    "Steering wheel angle": "Угол руля", "Steering angle": "Угол руля",
    "Lateral acceleration": "Поперечное ускорение", "Longitudinal acceleration": "Продольное ускорение", "Acceleration": "Ускорение",
    "Lateral g": "Поперечная перегрузка", "Longitudinal g": "Продольная перегрузка", "Yaw rate, sensor #": "Скорость поворота, датчик #",
    "Zero point of yaw rate": "Ноль датчика поворота", "Previous trip distance": "Прошлая поездка, км",
    "Distance with MIL": "Пробег с горящей «Check Engine»", "Distance traveled while mil was activated": "Пробег с горящей «Check Engine»",
    "Distance since DTC clear": "Пробег после сброса ошибок", "Warm-ups since DTC clear": "Прогревов после сброса ошибок",
    "Time since engine start": "Время с пуска двигателя", "Engine run time": "Моточасы", "Engine run time, total": "Моточасы, всего",
    # шины
    "Front left tire pressure": "Давление в шине, переднее левое", "Front right tire pressure": "Давление в шине, переднее правое",
    "Rear left tire pressure": "Давление в шине, заднее левое", "Rear right tire pressure": "Давление в шине, заднее правое",
    "Front left tire temperature": "Температура шины, переднее левое", "Front right tire temperature": "Температура шины, переднее правое",
    "Rear left tire temperature": "Температура шины, заднее левое", "Rear right tire temperature": "Температура шины, заднее правое",
    "Tire pressure #, set #": "Давление в шине #, комплект #",
    # двигатель
    "Engine RPM": "Обороты двигателя", "Engine speed": "Обороты двигателя", "Engine speed of cylinder #": "Обороты по цилиндру #",
    "Engine oil temperature": "Температура масла", "Engine oil temperature, variant #": "Температура масла, вариант #",
    "Calculated oil temperature": "Температура масла (расчётная)", "Engine oil pressure": "Давление масла", "Oil pressure": "Давление масла",
    "Engine oil level": "Уровень масла",
    "Coolant temperature": "Температура ОЖ", "Engine coolant temperature": "Температура ОЖ",
    "Cylinder head temperature": "Температура ГБЦ", "Variable valve timing oil temperature": "Температура масла фазовращателя",
    "Variable valve timing temperature": "Температура фазовращателя",
    "Engine torque": "Момент двигателя", "Torque, actual": "Момент фактический", "Engine load": "Нагрузка двигателя",
    "Engine load, actual": "Нагрузка фактическая", "Driver torque requested №#": "Запрошенный водителем момент №#",
    "Intake air temperature": "Температура впускного воздуха", "Intake air temperature #": "Температура впускного воздуха #",
    "Intake air temperature, bank # sensor #": "Температура впуска, ряд # датчик #", "Intake temperature, alternate": "Температура впуска (другой датчик)",
    "Intake temperature sensor voltage": "Датчик температуры впуска, напряжение", "Intake temperature fault": "Неисправность датчика температуры впуска",
    "Charge air temperature": "Температура наддувочного воздуха",
    "Ambient temperature": "Температура снаружи", "Ambient air temperature": "Температура снаружи", "Ambient temperature, alternate": "Температура снаружи (другой датчик)",
    "Barometric pressure": "Атмосферное давление", "Barometric pressure sensor voltage": "Датчик атмосферного давления, напряжение",
    "Manifold pressure": "Давление во впуске", "Manifold pressure sensor voltage": "Датчик давления во впуске, напряжение",
    "Boost pressure": "Давление наддува", "Turbo boost pressure": "Давление наддува", "Throttle inlet pressure": "Давление перед дросселем",
    "Throttle inlet pressure sensor voltage": "Давление перед дросселем, напряжение датчика",
    "Waste gate voltage": "Вестгейт, напряжение", "Waste gate duty": "Вестгейт, скважность",
    "Mass air flow": "Расход воздуха", "Air/gas ratio": "Соотношение воздух/топливо", "Fuel/air commanded equivalence ratio": "Заданная лямбда",
    "Accelerator pedal position": "Педаль газа", "Relative accelerator pedal position": "Педаль газа (относительно)",
    "Accelerator pedal position #": "Педаль газа, датчик #", "Accelerator pedal position sensor #": "Педаль газа, датчик #",
    "Accelerator pedal sensor #": "Педаль газа, датчик #", "Accelerator pedal, position #": "Педаль газа, датчик #",
    "Accelerator position": "Педаль газа", "Accelerator pedal": "Педаль газа", "Accelerator fully close learn #": "Педаль газа: обучение «отпущена» #",
    "Accelerator sensor out, number #": "Педаль газа, выход датчика #",
    "Throttle position №#": "Дроссель №#", "Throttle position, number #": "Дроссель, датчик #", "Throttle position sensor voltage №#": "Датчик дросселя №#, напряжение",
    "Throttle sensor open position #": "Дроссель: обучено «открыт» #", "Commanded throttle actuator": "Дроссель заданный",
    "Throttle position #": "Дроссель #", "Throttle position sensor voltage #": "Датчик дросселя #, напряжение",
    "Timing advance №#": "Угол опережения №#", "Timing advance #": "Угол опережения #", "Ignition advance": "Угол опережения",
    "Ignition timing, cylinder #": "Угол зажигания, цилиндр #", "Ignition correction, cylinder #": "Коррекция зажигания, цилиндр #",
    "Idle spark advance control, cylinder #": "Коррекция зажигания на ХХ, цилиндр #", "Idle spark advance control #": "Коррекция зажигания на ХХ #",
    "Timing angle retardation cylinder #": "Отскок угла, цилиндр #", "Ignition retard due to knock control #": "Отскок угла от детонации #",
    # детонация
    "Knock retard cylinder #": "Отскок угла от детонации, цилиндр #", "Knock retard cylinder №#": "Отскок угла от детонации, цилиндр №#",
    "Knock retard cylinder #, alternate": "Отскок угла от детонации, цилиндр # (другой расчёт)",
    "Knock retard, cylinder #": "Отскок угла от детонации, цилиндр #", "Knock counter, cylinder #": "Детонация, счётчик, цилиндр #",
    "Knock learn, cylinder #": "Детонация, обучение, цилиндр #", "Knock learn cylinder #": "Детонация, обучение, цилиндр #",
    "Knock sensor, #": "Датчик детонации #", "Knock sensor, cylinder #": "Датчик детонации, цилиндр #", "Knock sensor # test": "Датчик детонации #, проверка",
    "Knock detected": "Детонация обнаружена", "Knock control": "Контроль детонации", "Knock adjust": "Коррекция по детонации",
    "Knock feedback": "Обратная связь по детонации",
    "Early ignition counter, cylinder #": "Калильное зажигание, счётчик, цилиндр #",
    "Early ignition counter, total, cylinder #": "Калильное зажигание, всего, цилиндр #",
    # пропуски
    "Misfires, cylinder #": "Пропуски, цилиндр #", "Misfire count, cylinder #": "Пропуски, счётчик, цилиндр #",
    "Cylinder # misfire count": "Пропуски, цилиндр #", "Cylinder №# misfire count": "Пропуски, цилиндр №#",
    "Misfires, current, cylinder #": "Пропуски сейчас, цилиндр #", "Misfires current, cylinder #": "Пропуски сейчас, цилиндр #",
    "Misfire count №#": "Пропуски, цилиндр №#", "Misfire count №#, variant #": "Пропуски, цилиндр №#, вариант #",
    "Misfire count #,, variant #": "Пропуски, цилиндр #, вариант #",
    "Emission relevant misfires total, cylinder #": "Пропуски (токсичность), всего, цилиндр #",
    "Emission-relevant misfires, cylinder №#": "Пропуски (токсичность), цилиндр №#",
    "Emission relevant misfires total all cylinders": "Пропуски (токсичность), все цилиндры",
    "Catalyst damaging misfires total, cylinder #": "Пропуски, опасные для катализатора, цилиндр #",
    "Catalyst damaging misfires total all cylinders": "Пропуски, опасные для катализатора, все цилиндры",
    "Misfires harmful for catalyst cylinder #": "Пропуски, опасные для катализатора, цилиндр #",
    "Misfires harmful for catalyst, cylinder #": "Пропуски, опасные для катализатора, цилиндр #",
    "Misfires total counter, cylinder №#": "Пропуски, всего, цилиндр №#", "Misfires total": "Пропуски, всего",
    "Misfire acceleration, cylinder #": "Пропуски: неравномерность, цилиндр #", "Misfire history, cylinder #": "Пропуски, история, цилиндр #",
    "Misfires / # revolutions, cylinder #": "Пропуски на # оборотов, цилиндр #", "Cylinder # misfire average": "Пропуски, среднее, цилиндр #",
    "Rough idle, cylinder #": "Неровный ХХ, цилиндр #", "Balance rate, cylinder #": "Баланс цилиндра #",
    "Misfire load": "Нагрузка при пропусках", "Misfire margin": "Порог пропусков", "Misfire RPM": "Обороты при пропусках",
    "Compression test cylinder #": "Тест компрессии, цилиндр #",
    # топливо
    "Fuel level": "Уровень топлива", "Fuel level, volume": "Топливо в баке", "Fuel pressure #": "Давление топлива #",
    "Fuel temperature #": "Температура топлива #", "Fuel temperature": "Температура топлива", "Low fuel pressure": "Давление топлива, низкое",
    "High fuel pressure, actual": "Давление в рампе, фактическое", "High fuel pressure, target": "Давление в рампе, заданное",
    "Fuel pressure, low actual": "Давление топлива, контур низкого", "Engine fuel rate": "Расход топлива", "Fuel cut": "Отсечка топлива",
    "Fuel trim, short term": "Коррекция топлива краткосрочная", "Fuel trim, long term": "Коррекция топлива долгосрочная",
    "Evaporative purge": "Продувка адсорбера", "Mean injection quantity": "Цикловая подача, средняя",
    "Injection duration cylinder #": "Время впрыска, цилиндр #", "Injector pulse width, cylinder #": "Время впрыска, цилиндр #",
    "Injector pulse width": "Время впрыска", "Direct injection time #": "Время непосредственного впрыска #",
    "Gasoline direct injection time bank a, #": "Время прямого впрыска, ряд A, #", "Gasoline direct injection time bank b, #": "Время прямого впрыска, ряд B, #",
    "Injection volume #": "Объём впрыска #", "Cylinder # injection volume": "Объём впрыска, цилиндр #", "Cylinder # fuel quantity": "Подача топлива, цилиндр #",
    "Injection feedback value #": "Обратная связь впрыска #", "Injection amount deviation cylinder #": "Отклонение подачи, цилиндр #",
    "Fuel injection timing №#": "Момент впрыска №#", "Injector correction factor #": "Коррекция форсунки #",
    "Injector duty cycle bank #": "Загрузка форсунок, ряд #", "Fuel mass, cylinder #": "Масса топлива, цилиндр #",
    "Fuel mass correction, cylinder #": "Коррекция массы топлива, цилиндр #", "Required fuel charge, cylinder #": "Требуемая подача, цилиндр #",
    "Fuel injector time deviation, cylinder #": "Отклонение времени впрыска, цилиндр #",
    "Cylinder # fuel balance correction": "Балансировка подачи, цилиндр #",
    "A/F ratio learn value mid.#, bank #": "Обучение смеси, ряд #", "A/F ratio learned mid #, bank #": "Обучение смеси, ряд #",
    "Oxygen sensor # bank #": "Лямбда-зонд #, ряд #", "Post-injection # start position": "Доп. впрыск #, начало",
    # выпуск
    "Catalyst temperature": "Температура катализатора", "Catalyst temperature, bank # sensor #": "Температура катализатора, ряд # датчик #",
    "Catalyst temperature bank # sensor #": "Температура катализатора, ряд # датчик #",
    "Exhaust gas temperature": "Температура выхлопа", "Exhaust gas temperature #": "Температура выхлопа #",
    "Exhaust gas temperature sensor #": "Температура выхлопа, датчик #", "Exhaust gas recirculation temperature": "Температура ЕГР",
    "Diesel particulate filter differential pressure": "Сажевый фильтр: перепад давления",
    "Diesel particulate filter regeneration state": "Сажевый фильтр: регенерация",
    "Odometer at last DPF regeneration": "Пробег на последней регенерации сажевого", "Distance since last DPF regeneration": "Пробег после регенерации сажевого",
    # коробка
    "Transmission fluid temperature": "Температура масла КПП", "Transmission fluid temperature, variant #": "Температура масла КПП, вариант #",
    "Automatic transmission fluid temperature": "Температура масла АКПП", "Transmission oil temperature": "Температура масла КПП",
    "Dual-clutch transmission fluid temperature #": "Температура масла DSG #",
    "Current gear": "Текущая передача", "Gear": "Передача", "Commanded gear": "Заданная передача", "Transmission gear": "Передача",
    "Torque converter slip": "Проскальзывание гидротрансформатора", "Lock up state": "Блокировка гидротрансформатора",
    "Torque into torque converter": "Момент на входе ГДТ", "Turbine speed": "Обороты турбины ГДТ", "Output speed": "Обороты выходного вала",
    "Transmission output shaft speed": "Обороты выходного вала КПП", "Input shaft #": "Входной вал #", "Output shaft #": "Выходной вал #",
    "Transmission shift state ratio": "Ход переключения", "Torque converter time in temperature range #": "ГДТ: время в диапазоне температур #",
    "Operating time, temperature range #": "Время работы в диапазоне температур #",
    "Clutch # temperature": "Температура сцепления #", "Clutch №# temperature": "Температура сцепления №#",
    "Clutch №# maximum temperature": "Максимальная температура сцепления №#", "Clutch # actual pressure": "Давление сцепления #, фактическое",
    "Clutch # successful adaptations counter": "Сцепление #: удачных адаптаций", "Clutch # failed adaptations counter": "Сцепление #: неудачных адаптаций",
    "Specified torque clutch #": "Заданный момент сцепления #", "Clutch # valve current: specified": "Ток клапана сцепления #, заданный",
    "Clutch # valve current: actual": "Ток клапана сцепления #, фактический",
    "Transmission part # pressure commanded": "Давление в КПП, узел #, заданное", "Transmission part # pressure actual": "Давление в КПП, узел #, фактическое",
    "Transmission part # valve current commanded": "Ток клапана КПП, узел #, заданный", "Transmission part # valve current actual": "Ток клапана КПП, узел #, фактический",
    # батарея и электрика
    "Battery voltage": "Напряжение АКБ", "Battery current": "Ток АКБ", "Battery temperature": "Температура АКБ", "Battery temperature #": "Температура АКБ #",
    "Battery voltage, battery sensor": "Напряжение АКБ (датчик АКБ)", "Battery current, battery sensor": "Ток АКБ (датчик АКБ)",
    "Battery temperature, battery sensor": "Температура АКБ (датчик АКБ)", "Battery state of charge, battery sensor": "Заряд АКБ (датчик АКБ)",
    "#V battery voltage": "Напряжение АКБ #В", "#V battery current": "Ток АКБ #В", "#V battery temperature": "Температура АКБ #В",
    "#V battery state of charge": "Заряд АКБ #В", "State of charge": "Уровень заряда", "Control module voltage": "Напряжение блока",
    "Control module temperature": "Температура блока", "Electrical load signal #": "Нагрузка генератора #",
    "HV battery voltage": "Тяговая батарея: напряжение", "HV battery current": "Тяговая батарея: ток",
    "HV battery cell №# voltage": "Тяговая батарея: ячейка №#", "HV battery cell # voltage": "Тяговая батарея: ячейка #",
    "HV battery module # voltage": "Тяговая батарея: модуль #", "HV battery module # cell # voltage": "Тяговая батарея: модуль #, ячейка #",
    "HV battery module # cell # state of charge": "Тяговая батарея: заряд, модуль #, ячейка #",
    "HV battery module # min voltage": "Тяговая батарея: модуль #, мин. напряжение", "HV battery module # max voltage": "Тяговая батарея: модуль #, макс. напряжение",
    "HV battery module # min temperature": "Тяговая батарея: модуль #, мин. температура", "HV battery module # max temperature": "Тяговая батарея: модуль #, макс. температура",
    "HV battery block # voltage": "Тяговая батарея: блок #", "HV battery block # internal resistance": "Тяговая батарея: блок #, сопротивление",
    "HV battery temperature sensor #": "Тяговая батарея: датчик температуры #", "HV battery module # id": "Тяговая батарея: модуль #, номер",
    "Battery block # voltage": "Блок батареи #, напряжение", "Cell voltage #": "Ячейка #", "Cell # voltage": "Ячейка #", "Cell voltage, #": "Ячейка #",
    "Individual cell voltage A#": "Ячейка A#", "Internal resistance #": "Внутреннее сопротивление #", "Internal resistance R#": "Внутреннее сопротивление R#",
    "Temperature of battery #": "Температура батареи #", "Inverter temperature": "Температура инвертора",
    # тормоза, кузов, климат
    "Brake pressure": "Давление в тормозах", "Brake booster pressure": "Разрежение в усилителе тормозов", "Brake pedal": "Педаль тормоза",
    "Brake switch": "Выключатель тормоза", "Brake light": "Стоп-сигнал", "Stop light": "Стоп-сигнал", "Parking brake": "Стояночный тормоз",
    "Master cylinder sensor temperature": "Температура датчика ГТЦ",
    "Cabin temperature": "Температура в салоне", "Air conditioning pressure": "Давление кондиционера", "A/C compressor pressure": "Давление кондиционера",
    "A/C compressor pressure sensor voltage": "Датчик давления кондиционера, напряжение", "A/C compressor": "Компрессор кондиционера",
    "A/C compressor, state": "Компрессор кондиционера", "Air conditioning compressor torque": "Момент компрессора кондиционера",
    "Coolant fan # duty cycle": "Вентилятор радиатора #, загрузка", "Grill shutter duty cycle": "Жалюзи радиатора",
    "Coolant water route switching valve #, closed?": "Клапан контура ОЖ #, закрыт?",
    "Trunk open?": "Багажник открыт?", "Front left door open?": "Передняя левая дверь открыта?", "Front right door open?": "Передняя правая дверь открыта?",
    "Handbrake engaged?": "Ручник затянут?", "Headlight high beam": "Дальний свет", "Headlight low beam": "Ближний свет",
    "Hazard switch": "Аварийка", "Headlight switch": "Переключатель фар", "Defog light": "Противотуманные фары",
    "Right turn signal on?": "Правый поворотник?", "Left turn signal on?": "Левый поворотник?", "Reverse gear selected": "Включена задняя",
}

root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
assets = os.path.join(root, "app", "src", "main", "assets", "obdb")
names = json.load(open(os.path.join(assets, "index.json"), encoding="utf-8"))["names"]
out = {}
for n in names:
    n2 = re.sub(r"#(\d)", r"№\1", n)
    t = T.get(re.sub(r"\d+", "#", n2))
    if t is None:
        continue
    nums = re.findall(r"\d+", n2)
    if t.count("#") != len(nums):
        continue
    for x in nums:
        t = t.replace("#", x, 1)
    out[n] = t
json.dump(out, open(os.path.join(assets, "names_ru.json"), "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))

# доля переведённых сигналов по всем моделям
import collections
idx = json.load(open(os.path.join(assets, "index.json"), encoding="utf-8"))
tot = done = 0
for m in idx["models"]:
    d = json.load(open(os.path.join(assets, m["r"] + ".json"), encoding="utf-8"))
    for c in d["cmd"]:
        for s in c["s"]:
            tot += 1; done += names[s[0]] in out
print(f"шаблонов {len(T)}, названий переведено {len(out)} из {len(names)}, сигналов {done} из {tot} ({done * 100 // tot} %)")
