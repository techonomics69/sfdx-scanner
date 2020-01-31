import shell = require("shelljs");
import path = require("path");
import process = require("process");

const base = path.join('dist', 'pmd', 'bin');
const pmdRunScript = path.join(`${base}`, 'run.sh') + " pmd";
const pmdRunWin = path.join(`${base}`, 'pmd.bat');


/**
 * Output format supported by PMD
 */
export enum Format {
    XML = "xml",
    CSV = "csv",
    TEXT = "txt"
}

/**
 * Wrapper around PMD interactions
 */
export default class PmdWrapper {

    source: string;
    rules: string;
    reportFormat: Format;
    reportFile: string;


    constructor(source: string, rules: string, reportFormat: Format, reportFile: string) {
        this.source = source;
        this.rules = rules;
        this.reportFormat = reportFormat;
        this.reportFile = reportFile;
    }

    public static async execute(source: string, rules: string, reportFormat: Format, reportFile: string) {
        const myPmd = new PmdWrapper(source, rules, reportFormat, reportFile);
        myPmd.runPmd();
    }

    async runPmd() {
        const runCommand = this.getRunCommand();
        shell.exec(`${runCommand} -rulesets ${this.rules} -dir ${this.source} -format ${this.reportFormat} -reportfile ${this.reportFile}`);
    }

    getRunCommand(): string {
        if (this.isWindows()) {
            return pmdRunWin;
        } else {
            return pmdRunScript;
        }
    }

    isWindows(): boolean {
        return process.platform === "win32";
    }
}