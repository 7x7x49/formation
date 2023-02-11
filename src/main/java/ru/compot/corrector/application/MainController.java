package ru.compot.corrector.application;


import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Bounds;
import javafx.scene.Group;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import ru.compot.corrector.core.AnalyzedRegion;
import ru.compot.corrector.core.AnalyzerCore;
import ru.compot.corrector.core.AnalyzerOutput;
import ru.compot.corrector.utils.splash.SplashScreenUtils;

import java.io.*;
import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class MainController implements Initializable {

    private final Stage primaryStage;
 
    @FXML
    private TextArea input;
  
    @FXML
    private TextArea output;
 
    @FXML
    private Group analyzeState;

    @FXML
    private ContextMenu outputContextMenu;
  
    @FXML
    private Group linesGroup;

    @FXML
    private Button openButton;
  
    @FXML
    private Button analyzeButton;
  
    @FXML
    private Button saveButton;
   
    @FXML
    private CheckMenuItem paragraphState;
  
    @FXML
    private MenuItem sentencesLabel;
   
    @FXML
    private Slider sentencesSlider;
  
    @FXML
    private CheckMenuItem englishCheck;
    private AnalyzerCore core;

    private File savePath;
 
    private String saveFile = "corrector-output";

    private boolean skipOutputChangeEvent;

    public MainController(Stage primaryStage) {
        this.primaryStage = primaryStage;
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        core = new AnalyzerCore();
        
        sentencesLabel.setText("Предложений в абзаце: " + core.getSentencesInParagraph()); // устанавливаем текст над слайдером кол-ва предложений в абзаце в меню Настроек
        sentencesSlider.setValue(core.getSentencesInParagraph());
        paragraphState.setSelected(core.isParagraphsEnabled()); // устанавливаем сохраненное значение разделения текста на абзацы
        englishCheck.setSelected(core.isEnglishEnabled());
        
        output.textProperty().addListener(((observable, oldValue, newValue) -> { // отловщик события изменения текста в поле для вывода
            if (skipOutputChangeEvent) {
                skipOutputChangeEvent = false;
                return; // скипаем его
            }
            if (core.getAnalyzedRegions().size() > 0) { // если список проанализируемых регионов не пуст
                core.getAnalyzedRegions().clear(); 
            }
        }));
        output.scrollTopProperty().addListener(((observable, oldValue, newValue) -> { // отловщик события скролла поля для вывода
            AnalyzedRegion.yOffset = -newValue.doubleValue();
        }));
        sentencesSlider.valueProperty().addListener((observable, oldValue, newValue) ->
                sentencesLabel.setText("Предложений в абзаце: " + newValue.intValue()));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> core.save()));
    }

    /**
     * Отловщик события нажатия мышкой на поле для вывода
     * @param event информация события
     */
    @FXML
    private void onOutputClick(MouseEvent event) {
        if (core.getAnalyzedRegions().size() < 1 || event.getButton() != MouseButton.SECONDARY) return; // если лист проанализированных регионов пустой или нажата не правая кнопка мыши
        List<AnalyzedRegion> regions = core.getAnalyzedRegions().stream() 
                .filter(r -> output.getLayoutBounds().contains(r.x, r.y + AnalyzedRegion.yOffset, r.width, r.height)) 
                .filter(r -> {
                    double x = event.getSceneX() - output.getLayoutX();
                    double y = event.getSceneY() - output.getLayoutY();
                    return x >= r.x
                            && x <= r.x + r.width
                            && y >= r.y + AnalyzedRegion.yOffset
                            && y <= r.y + AnalyzedRegion.yOffset + r.height; // получаем те, на которые было произведено нажатие
                }).toList();
        outputContextMenu.getItems().clear();
        regions.forEach(r -> {
            Menu menu = new Menu(r.replacement);
            MenuItem mi = addListenerToMenuItem(new MenuItem(r.source), r.source, r);
            menu.getItems().add(mi);
            r.allReplacements.forEach(rep -> menu.getItems().add(addListenerToMenuItem(new MenuItem(rep), rep, r)));
            outputContextMenu.getItems().add(menu); // добавляем в контекстное меню
        });
    }
    
    private MenuItem addListenerToMenuItem(MenuItem mi, String replacement, AnalyzedRegion region) {
        mi.addEventHandler(ActionEvent.ANY, me -> {
            String oldReplacement = region.replacement;
            region.replacement = replacement; 

            String part1 = output.getText(0, region.from); // получаем текст до региона
            String part2 = output.getText(region.from + oldReplacement.length(), output.getLength()); 
            skipOutputChangeEvent = true;
            output.setText(part1 + region.replacement + part2); // изменяем текст в поле для вывода

            Bounds areaBounds = output.lookup(".text").getBoundsInParent(); // получаем границы текстового поля дял вывода
            region.updatePosition(areaBounds, output.getFont(), part1);
            core.getAnalyzedRegions().stream() // создаем поток регионов
                    .filter(r1 -> r1.from >= region.from && r1 != region)
                    .forEach(anotherRegion -> { 
                        anotherRegion.from += region.replacement.length() - oldReplacement.length();
                        String anotherPart1 = output.getText(0, anotherRegion.from);
                        anotherRegion.updatePosition(areaBounds, output.getFont(), anotherPart1);
                    });
        });
        return mi;
    }

    @FXML
    private void onAnalyzeClick() {
        setAnalyzeState(true); // говорим приложению, что анализ начался
        core.getAnalyzedRegions().clear(); 
        output.setText(input.getText()); // записываем в поле для вывода текст из поля для ввода
        Service<AnalyzerOutput> service = new Service<>() {
            @Override
            protected Task<AnalyzerOutput> createTask() {
                return new Task<>() {
                    @Override
                    protected AnalyzerOutput call() {
                        return core.analyze(input.getText()); // запускаем анализ в отдельном потоке
                    }
                };
            }
        };
        service.setOnSucceeded((event) -> { // если поток был выполнен успешно
            AnalyzerOutput out = service.getValue();
            if (out == null) {
                output.setText("Ошибка при анализе текста");
                SplashScreenUtils.displayInfoScreen(primaryStage, "error.png", "Fail", "Не удалось анализировать текст");
            } else { // иначе
                ConcurrentHashMap<Integer, Integer> offsets = new ConcurrentHashMap<>(); 
                calculateOffsetsAndApplyText(out, offsets); 
                Bounds areaBounds = output.lookup(".text").getBoundsInParent();
                core.applyAnalyzedRegions(out, offsets, output.getText(), areaBounds, output.getFont()); 
                SplashScreenUtils.displayInfoScreen(primaryStage, "success.png", "Success", "Текст обработан успешно!");
            }
            setAnalyzeState(false); 
        });
        service.start();
    }


    private void calculateOffsetsAndApplyText(AnalyzerOutput out, ConcurrentHashMap<Integer, Integer> offsets) {
        out.matches().forEach(rm -> {
            int offset = AnalyzerCore.getOffset(offsets, rm.getFromPos()); 
            String source = input.getText(rm.getFromPos(), rm.getToPos()); 
            String replacement = rm.getSuggestedReplacements().size() > 0 ? rm.getSuggestedReplacements().get(0) : source; 
            String part1 = output.getText(0, rm.getFromPos() + offset); 
            String part2 = output.getText(rm.getToPos() + offset, output.getLength()); 
            skipOutputChangeEvent = true; 
            output.setText(part1 + replacement + part2); 
            int newOffset = replacement.length() - (rm.getToPos() - rm.getFromPos()); // вычисляем смещение этого региона
            if (newOffset == 0) return; // если оно 0, не добавляем в карту смещений
            if (offsets.containsKey(rm.getToPos())) { // если в карте смещений существует смещение по позиции этого региона
                offsets.replace(rm.getToPos(), offsets.get(rm.getToPos()) + newOffset); // складываем прошлое и текущее смещения
                return;
            }
            offsets.put(rm.getToPos(), newOffset); // иначе записываем новое смещение
        });
    }

    /**
     * Включает/выключает отображение элементов на экране, отвечающие за текущее состояние анализа текста
     * @param state анализируется ли сейчас текст или нет
     */
    private void setAnalyzeState(boolean state) {
        openButton.setDisable(state); 
        analyzeButton.setDisable(state); 
        saveButton.setDisable(state);
        input.setDisable(state); 
        output.setDisable(state);
        analyzeState.setVisible(state);
    }

    /**
     * Отловщик события нажатия мышкой на кнопку открытия файла
     */
    @FXML
    private void onOpenClick() {
        openButton.setDisable(true); 
        File file = new FileChooser().showOpenDialog(null); // открываем выбор файла
        openButton.setDisable(false); // разлокируем кнопку открытия файла
        if (file == null) return;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) { 
            savePath = file.getParentFile(); 
            saveFile = file.getName();
            input.setText(br.lines().collect(Collectors.joining("\n"))); // задаем текст полю для ввода
        } catch (IOException e) { 
            SplashScreenUtils.displayInfoScreen(primaryStage, "error.png", "Fail", "Не удалось открыть файл"); 
            e.printStackTrace();
        }
    }

    /**
     * Отловщик события нажатия мышкой на кнопку сохранения файла
     */
    @FXML
    private void onSaveClick() {
        saveButton.setDisable(true); // блокируем кнопку сохранения
        FileChooser fc = new FileChooser();
        FileChooser.ExtensionFilter ef = new FileChooser.ExtensionFilter("Текстовый документ", "*.txt"); // задаем возможные расширения для сохранения файла
        fc.getExtensionFilters().add(ef);
        fc.setSelectedExtensionFilter(ef);
        fc.setInitialDirectory(savePath); 
        fc.setInitialFileName(saveFile);
        File file = fc.showSaveDialog(null); 
        saveButton.setDisable(false); 
        if (file == null) return; 
        try (FileWriter fw = new FileWriter(file)) { 
            if (core.isParagraphsEnabled()) { // если разделение на абзацы включено
                String[] sentences = output.getText().split("[.?!]\s+"); // делим текст на предложения
                int countSentences = 0;
                StringBuilder newText = new StringBuilder("\t");
                for (String sentence : sentences) { // проходимся по каждому предложению
                    countSentences++; // плюсуем в кол-во предложений
                    if (countSentences > core.getSentencesInParagraph()) { // если кол-во предложений в абзаце, больше чем в настройке
                        countSentences = 0; // обнуляем кол-во
                        newText.append("\n\t").append(sentence).append(". "); // заносим текст с новым абзацем
                        continue;
                    }
                    newText.append(sentence).append(". "); // иначе просто заносим текст
                }
                fw.write(newText.toString());
            } else fw.write(output.getText());
            fw.flush();
        } catch (IOException e) {
            SplashScreenUtils.displayInfoScreen(primaryStage, "error.png", "Fail", "Не удалось сохранить файл");
            e.printStackTrace();
        }
    }

    /**
     * Отловщик события нажатия мышкой на чекбокс Разделение на абзацы в меню Preferences
     */
    @FXML
    private void onParagraphStateClick() {
        core.setParagraphsEnabled(paragraphState.isSelected());
    }

    /**
     * Отловщик события нажатия мышкой на слайдер регулирования кол-ва предложений в абзаце в меню Preferences
     */
    @FXML
    private void onSliderClick() {
        int sentences = (int) sentencesSlider.getValue();
        core.setSentencesInParagraph(sentences);
    }

    /**
     * Отловщик события нажатия мышкой на чекбокс English в меню Preferences
     */
    @FXML
    private void onEnglishClick() {
        core.setEnglishEnabled(englishCheck.isSelected());
    }
}
